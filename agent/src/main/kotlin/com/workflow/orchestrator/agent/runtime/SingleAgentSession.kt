package com.workflow.orchestrator.agent.runtime

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.ChatMessage
import com.workflow.orchestrator.agent.api.dto.ToolDefinition
import com.workflow.orchestrator.agent.brain.LlmBrain
import com.workflow.orchestrator.agent.context.ContextManager
import com.workflow.orchestrator.agent.orchestrator.AgentProgress
import com.workflow.orchestrator.agent.orchestrator.OrchestratorPrompts
import com.workflow.orchestrator.agent.security.OutputValidator
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.core.model.ApiResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Result of a single-agent session execution.
 */
sealed class SingleAgentResult {
    /** Task completed successfully in a single agent pass. */
    data class Completed(
        val content: String,
        val summary: String,
        val tokensUsed: Int,
        val artifacts: List<String>
    ) : SingleAgentResult()

    /** Task failed with an error. */
    data class Failed(
        val error: String,
        val tokensUsed: Int
    ) : SingleAgentResult()

    /** Task is too complex for single agent — escalate to orchestrated mode. */
    data class EscalateToOrchestrated(
        val reason: String,
        val partialContext: String,
        val tokensUsed: Int
    ) : SingleAgentResult()
}

/**
 * The default execution path: a single ReAct loop with ALL tools available.
 *
 * Unlike [WorkerSession] which is scoped to a specific worker type with filtered tools,
 * SingleAgentSession gives the LLM access to every registered tool. The LLM decides
 * whether to analyze, code, review, or interact with enterprise tools — all in one
 * conversation.
 *
 * Includes:
 * - [BudgetEnforcer] check before each LLM call
 * - Auto-escalation to orchestrated mode if budget is exceeded
 * - [OutputValidator] on final content
 * - Progress callbacks for each iteration
 *
 * Max iterations: 15 (higher than WorkerSession's 10, since this handles full tasks).
 */
class SingleAgentSession(
    private val maxIterations: Int = 15
) {
    companion object {
        private val LOG = Logger.getInstance(SingleAgentSession::class.java)
        private val json = Json { ignoreUnknownKeys = true }
    }

    /**
     * Execute the single-agent ReAct loop.
     *
     * @param task The full task description from the user
     * @param tools Map of tool name to AgentTool (all registered tools)
     * @param toolDefinitions Tool definitions for the LLM
     * @param brain The LLM brain
     * @param contextManager Manages conversation history with compression
     * @param project The IntelliJ project
     * @param onProgress Callback for progress updates
     * @return [SingleAgentResult] — completed, failed, or escalation signal
     */
    /**
     * Execute the single-agent ReAct loop.
     *
     * @param task The full task description from the user
     * @param tools Map of tool name to AgentTool (all registered tools)
     * @param toolDefinitions Tool definitions for the LLM
     * @param brain The LLM brain
     * @param contextManager Manages conversation history with compression
     * @param project The IntelliJ project
     * @param approvalGate Optional gate for risk-based approval of tool actions
     * @param onProgress Callback for progress updates
     * @param onStreamChunk Callback for streaming LLM output tokens (for real-time UI)
     * @return [SingleAgentResult] — completed, failed, or escalation signal
     */
    suspend fun execute(
        task: String,
        tools: Map<String, AgentTool>,
        toolDefinitions: List<ToolDefinition>,
        brain: LlmBrain,
        contextManager: ContextManager,
        project: Project,
        approvalGate: ApprovalGate? = null,
        onProgress: (AgentProgress) -> Unit = {},
        onStreamChunk: (String) -> Unit = {}
    ): SingleAgentResult {
        LOG.info("SingleAgentSession: starting with ${tools.size} tools for task: ${task.take(100)}")

        val maxTokens = contextManager.remainingBudget() + contextManager.currentTokens
        val budgetEnforcer = BudgetEnforcer(contextManager, maxTokens)

        // Build a combined system prompt from all worker prompts
        val systemPrompt = buildSystemPrompt()

        // Initialize context
        contextManager.addMessage(ChatMessage(role = "system", content = systemPrompt))
        contextManager.addMessage(ChatMessage(role = "user", content = task))

        var totalTokensUsed = 0
        val allArtifacts = mutableListOf<String>()

        for (iteration in 1..maxIterations) {
            LOG.info("SingleAgentSession: iteration $iteration/$maxIterations")
            onProgress(AgentProgress(
                step = "Thinking... (iteration $iteration)",
                tokensUsed = totalTokensUsed
            ))

            // Budget check before each LLM call
            when (budgetEnforcer.check()) {
                BudgetEnforcer.BudgetStatus.ESCALATE -> {
                    LOG.warn("SingleAgentSession: budget critical at iteration $iteration, escalating")
                    val contextSummary = buildContextSummary(contextManager)
                    return SingleAgentResult.EscalateToOrchestrated(
                        reason = "Token budget exceeded (${budgetEnforcer.utilizationPercent()}% utilization) at iteration $iteration",
                        partialContext = contextSummary,
                        tokensUsed = totalTokensUsed
                    )
                }
                BudgetEnforcer.BudgetStatus.COMPRESS -> {
                    LOG.info("SingleAgentSession: triggering compression at iteration $iteration")
                    // ContextManager handles compression internally when messages are added;
                    // this status just means we're approaching limits. If compression doesn't
                    // help enough, the next check will return ESCALATE.
                }
                BudgetEnforcer.BudgetStatus.OK -> { /* proceed */ }
            }

            val messages = contextManager.getMessages()
            val activeToolDefs = if (tools.isNotEmpty()) toolDefinitions else null

            // Use streaming when onStreamChunk callback is provided, otherwise batch
            val result = try {
                brain.chatStream(messages, activeToolDefs) { chunk ->
                    // Pipe streaming content deltas to the UI for real-time display
                    chunk.choices.firstOrNull()?.delta?.content?.let { delta ->
                        onStreamChunk(delta)
                    }
                }
            } catch (_: NotImplementedError) {
                // Fall back to non-streaming if chatStream isn't implemented
                brain.chat(messages, activeToolDefs)
            }

            when (result) {
                is ApiResult.Success -> {
                    val response = result.data
                    val usage = response.usage
                    if (usage != null) {
                        totalTokensUsed += usage.totalTokens
                    }

                    val choice = response.choices.firstOrNull() ?: break
                    val message = choice.message
                    val toolCalls = message.toolCalls

                    // Add assistant message to context
                    contextManager.addAssistantMessage(message)

                    if (toolCalls.isNullOrEmpty()) {
                        // No tool calls — final response
                        val content = message.content ?: ""

                        // Validate output for sensitive data
                        val securityIssues = OutputValidator.validate(content)
                        if (securityIssues.isNotEmpty()) {
                            LOG.warn("SingleAgentSession: output validation flagged: ${securityIssues.joinToString()}")
                        }

                        val summary = if (content.length > 200) content.take(200) + "..." else content
                        LOG.info("SingleAgentSession: completed after $iteration iterations, $totalTokensUsed tokens")

                        onProgress(AgentProgress(
                            step = "Task completed",
                            tokensUsed = totalTokensUsed
                        ))

                        return SingleAgentResult.Completed(
                            content = content,
                            summary = summary,
                            tokensUsed = totalTokensUsed,
                            artifacts = allArtifacts
                        )
                    }

                    // Execute tool calls with approval gate
                    for (toolCall in toolCalls) {
                        val toolName = toolCall.function.name
                        val tool = tools[toolName]

                        if (tool == null) {
                            contextManager.addToolResult(
                                toolCallId = toolCall.id,
                                content = "Error: Tool '$toolName' not found. Available tools: ${tools.keys.joinToString(", ")}",
                                summary = "Tool not found: $toolName"
                            )
                            continue
                        }

                        // Check approval gate before executing risky tools
                        if (approvalGate != null) {
                            val riskLevel = ApprovalGate.riskLevelFor(toolName)
                            val approval = approvalGate.check(
                                toolName = toolName,
                                description = "$toolName(${toolCall.function.arguments.take(100)})",
                                riskLevel = riskLevel
                            )
                            when (approval) {
                                is ApprovalResult.Rejected -> {
                                    contextManager.addToolResult(
                                        toolCallId = toolCall.id,
                                        content = "Tool call rejected by user. The user chose not to allow this action.",
                                        summary = "Rejected: $toolName"
                                    )
                                    continue
                                }
                                is ApprovalResult.Pending -> {
                                    contextManager.addToolResult(
                                        toolCallId = toolCall.id,
                                        content = "Tool call pending user approval. Waiting for user decision.",
                                        summary = "Pending approval: $toolName"
                                    )
                                    continue
                                }
                                is ApprovalResult.Approved -> { /* proceed */ }
                            }
                        }

                        try {
                            val params = json.decodeFromString<JsonObject>(toolCall.function.arguments)
                            val toolResult = tool.execute(params, project)

                            contextManager.addToolResult(
                                toolCallId = toolCall.id,
                                content = toolResult.content,
                                summary = toolResult.summary
                            )

                            allArtifacts.addAll(toolResult.artifacts)

                            onProgress(AgentProgress(
                                step = "Used tool: $toolName",
                                tokensUsed = totalTokensUsed
                            ))
                        } catch (e: Exception) {
                            LOG.warn("SingleAgentSession: tool '$toolName' failed", e)
                            contextManager.addToolResult(
                                toolCallId = toolCall.id,
                                content = "Error executing tool '$toolName': ${e.message}",
                                summary = "Tool error: $toolName"
                            )
                        }
                    }
                }

                is ApiResult.Error -> {
                    LOG.warn("SingleAgentSession: LLM call failed: ${result.message}")
                    return SingleAgentResult.Failed(
                        error = "LLM call failed: ${result.message}",
                        tokensUsed = totalTokensUsed
                    )
                }
            }
        }

        LOG.warn("SingleAgentSession: reached max iterations ($maxIterations)")
        return SingleAgentResult.Failed(
            error = "Reached maximum iterations ($maxIterations) without completing",
            tokensUsed = totalTokensUsed
        )
    }

    /**
     * Build a combined system prompt that gives the LLM all capabilities.
     * For now, concatenates the key parts of each worker prompt.
     * Task 2 will replace this with dynamic PromptAssembler.
     */
    private fun buildSystemPrompt(): String {
        return """
            You are an AI coding assistant for the Workflow Orchestrator IntelliJ plugin.
            You have access to all tools and can analyze code, edit files, review changes,
            and interact with enterprise tools (Jira, Bamboo, SonarQube, Bitbucket).

            <capabilities>
            - Read and analyze code using PSI-based tools (find references, type hierarchy, call graph)
            - Edit files precisely using the edit_file tool
            - Search code across the project
            - Run shell commands when needed
            - Interact with Jira (read tickets, update status, add comments, log time)
            - Check Bamboo build status and trigger builds
            - Query SonarQube for issues and coverage
            - Create Bitbucket pull requests
            </capabilities>

            <rules>
            - Always read files before editing them to understand the full context.
            - Make minimal, focused edits. Don't rewrite entire files.
            - Preserve existing code style (indentation, naming, comments).
            - After editing, run diagnostics to verify no compilation errors.
            - For IntelliJ plugin code: never block the EDT, use suspend functions for I/O.
            - Handle errors gracefully and report what happened.
            - Never store, log, or output credentials, tokens, or secrets.
            - Confirm destructive actions before executing them.
            </rules>

            <output>
            When you've completed the task, provide a clear summary of what you did,
            what files were changed, and any issues encountered.
            </output>
        """.trimIndent()
    }

    /**
     * Build a summary of the current context for escalation handoff.
     */
    private fun buildContextSummary(contextManager: ContextManager): String {
        val messages = contextManager.getMessages()
        val toolResults = messages.filter { it.role == "tool" }
        val assistantMessages = messages.filter { it.role == "assistant" }

        return buildString {
            appendLine("Partial context from single-agent session:")
            appendLine("- Messages exchanged: ${messages.size}")
            appendLine("- Tool calls made: ${toolResults.size}")
            if (assistantMessages.isNotEmpty()) {
                val lastAssistant = assistantMessages.last()
                appendLine("- Last assistant response: ${(lastAssistant.content ?: "").take(200)}")
            }
        }
    }
}

package com.workflow.orchestrator.agent.runtime

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.ChatMessage
import com.workflow.orchestrator.agent.api.dto.ToolDefinition
import com.workflow.orchestrator.agent.brain.LlmBrain
import com.workflow.orchestrator.agent.context.ContextManager
import com.workflow.orchestrator.agent.security.OutputValidator
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.core.model.ApiResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Result of a worker session execution.
 */
data class WorkerResult(
    val content: String,
    val summary: String,
    val tokensUsed: Int,
    val artifacts: List<String> = emptyList(),
    val isError: Boolean = false
)

/**
 * Executes a single worker lifecycle using the ReAct (Reason + Act) loop.
 *
 * Loop: send message to brain with tools ->
 *   if tool_calls in response, execute tools, add results, repeat ->
 *   if no tool_calls, return final response.
 *
 * Max iterations: 10 to prevent infinite loops.
 *
 * Supports optional transcript recording for resume capability.
 * Pass a [transcriptStore] and [agentId] to persist all messages to JSONL.
 */
class WorkerSession(
    private val maxIterations: Int = 10,
    private val parentJob: kotlinx.coroutines.Job? = null,
    private val transcriptStore: WorkerTranscriptStore? = null,
    val agentId: String = WorkerTranscriptStore.generateAgentId()
) {
    companion object {
        private val LOG = Logger.getInstance(WorkerSession::class.java)
        private val json = Json { ignoreUnknownKeys = true }
    }

    /**
     * Record a message to the transcript store if available.
     */
    private fun recordMessage(role: String, content: String?, toolCallId: String? = null) {
        transcriptStore?.appendMessage(agentId, WorkerTranscriptStore.TranscriptMessage(
            role = role,
            content = content,
            toolCallId = toolCallId
        ))
    }

    /**
     * Execute a worker session with the ReAct loop.
     *
     * @param workerType The type of worker (ANALYZER, CODER, etc.)
     * @param systemPrompt The system prompt for this worker
     * @param task The task description/instructions
     * @param tools Map of tool name to AgentTool (available tools for this worker)
     * @param toolDefinitions The tool definitions to send to the LLM
     * @param brain The LLM brain
     * @param contextManager Manages conversation history with compression
     * @param project The IntelliJ project (needed for tool execution)
     * @return WorkerResult with the final response and metadata
     */
    suspend fun execute(
        workerType: WorkerType,
        systemPrompt: String,
        task: String,
        tools: Map<String, AgentTool>,
        toolDefinitions: List<ToolDefinition>,
        brain: LlmBrain,
        contextManager: ContextManager,
        project: Project,
        maxOutputTokens: Int? = null
    ): WorkerResult {
        LOG.info("WorkerSession: starting $workerType worker for task: ${task.take(100)}")

        // Initialize context with system prompt and task
        contextManager.addMessage(ChatMessage(role = "system", content = systemPrompt))
        recordMessage("system", systemPrompt)

        contextManager.addMessage(ChatMessage(role = "user", content = task))
        recordMessage("user", task)

        return runReactLoop(tools, toolDefinitions, brain, contextManager, project, maxOutputTokens)
    }

    /**
     * Execute the ReAct loop from an already-populated ContextManager.
     * Used for resume — the context already contains the previous conversation.
     */
    suspend fun executeFromContext(
        tools: Map<String, AgentTool>,
        toolDefinitions: List<ToolDefinition>,
        brain: LlmBrain,
        contextManager: ContextManager,
        project: Project,
        maxOutputTokens: Int? = null
    ): WorkerResult {
        LOG.info("WorkerSession: resuming agent $agentId from existing context")
        return runReactLoop(tools, toolDefinitions, brain, contextManager, project, maxOutputTokens)
    }

    /**
     * Core ReAct loop shared by [execute] and [executeFromContext].
     */
    private suspend fun runReactLoop(
        tools: Map<String, AgentTool>,
        toolDefinitions: List<ToolDefinition>,
        brain: LlmBrain,
        contextManager: ContextManager,
        project: Project,
        maxOutputTokens: Int? = null
    ): WorkerResult {
        var totalTokensUsed = 0
        val allArtifacts = mutableListOf<String>()
        var consecutiveNoToolResponses = 0

        for (iteration in 1..maxIterations) {
            if (parentJob?.isActive == false) {
                LOG.info("WorkerSession: cancelled by parent at iteration $iteration")
                return WorkerResult(
                    content = "Worker cancelled by parent session.",
                    summary = "Cancelled at iteration $iteration",
                    tokensUsed = totalTokensUsed,
                    artifacts = allArtifacts,
                    isError = true
                )
            }

            LOG.info("WorkerSession: iteration $iteration/$maxIterations")

            val messages = contextManager.getMessages()
            val activeToolDefs = if (tools.isNotEmpty()) toolDefinitions else null

            val result = brain.chat(messages, activeToolDefs, maxOutputTokens)

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
                    recordMessage("assistant", message.content)

                    if (toolCalls.isNullOrEmpty()) {
                        val content = message.content ?: ""
                        consecutiveNoToolResponses++

                        // First no-tool response with iterations remaining: nudge to use worker_complete
                        if (consecutiveNoToolResponses == 1 && iteration < maxIterations) {
                            LOG.info("WorkerSession: no tool calls at iteration $iteration — nudging to use worker_complete")
                            val nudge = "You responded without calling any tools. " +
                                "If you have completed the task, call worker_complete with the COMPLETE output " +
                                "of your work — the orchestrator only sees your worker_complete result, not your " +
                                "tool call history. If you have more work to do, make your next tool call now."
                            contextManager.addMessage(ChatMessage(role = "user", content = nudge))
                            recordMessage("user", nudge)
                            continue
                        }

                        // Second consecutive no-tool response (or last iteration): force-accept escape hatch
                        LOG.warn("WorkerSession: force-accepting after $consecutiveNoToolResponses no-tool responses at iteration $iteration")
                        val securityIssues = OutputValidator.validate(content)
                        if (securityIssues.isNotEmpty()) {
                            LOG.warn("WorkerSession: output validation flagged: ${securityIssues.joinToString()}")
                        }
                        val summary = if (content.length > 200) content.take(200) + "..." else content
                        LOG.info("WorkerSession: completed (force-accept) after $iteration iterations")
                        return WorkerResult(
                            content = content,
                            summary = summary,
                            tokensUsed = totalTokensUsed,
                            artifacts = allArtifacts,
                            isError = false
                        )
                    }

                    consecutiveNoToolResponses = 0

                    // Execute tool calls and add results
                    for (toolCall in toolCalls) {
                        val toolName = toolCall.function.name

                        if (parentJob?.isActive == false) {
                            LOG.info("WorkerSession: cancelled before tool '$toolName' execution")
                            return WorkerResult("Cancelled", "Cancelled", totalTokensUsed, allArtifacts, isError = true)
                        }

                        val tool = tools[toolName]

                        if (tool == null) {
                            val availableTools = tools.keys.joinToString(", ")
                            val errorContent = "Error: Tool '$toolName' is not available. Available tools: $availableTools. Please use one of these."
                            contextManager.addToolResult(
                                toolCallId = toolCall.id,
                                content = errorContent,
                                summary = "Tool not found: $toolName"
                            )
                            recordMessage("tool", errorContent, toolCallId = toolCall.id)
                            continue
                        }

                        try {
                            val params = json.decodeFromString<JsonObject>(toolCall.function.arguments)
                            val toolResult = tool.execute(params, project)

                            contextManager.addToolResult(
                                toolCallId = toolCall.id,
                                content = toolResult.content,
                                summary = toolResult.summary
                            )
                            recordMessage("tool", toolResult.content, toolCallId = toolCall.id)

                            allArtifacts.addAll(toolResult.artifacts)

                            // Exit early when worker signals completion via worker_complete
                            if (toolResult.isCompletion) {
                                LOG.info("WorkerSession: worker_complete called at iteration $iteration — exiting loop")
                                return WorkerResult(
                                    content = toolResult.content,
                                    summary = toolResult.summary,
                                    tokensUsed = totalTokensUsed,
                                    artifacts = allArtifacts,
                                    isError = false
                                )
                            }
                        } catch (e: Exception) {
                            LOG.warn("WorkerSession: tool '$toolName' failed", e)
                            val errorContent = "Error executing tool '$toolName': ${e.message}"
                            contextManager.addToolResult(
                                toolCallId = toolCall.id,
                                content = errorContent,
                                summary = "Tool error: $toolName"
                            )
                            recordMessage("tool", errorContent, toolCallId = toolCall.id)
                        }
                    }
                }

                is ApiResult.Error -> {
                    LOG.warn("WorkerSession: LLM call failed: ${result.message}")
                    return WorkerResult(
                        content = "Error: LLM call failed: ${result.message}",
                        summary = "Failed: ${result.message}",
                        tokensUsed = totalTokensUsed,
                        artifacts = allArtifacts,
                        isError = true
                    )
                }
            }
        }

        LOG.warn("WorkerSession: reached max iterations ($maxIterations)")
        return WorkerResult(
            content = "Reached maximum iterations ($maxIterations) without completing",
            summary = "Incomplete: max iterations reached",
            tokensUsed = totalTokensUsed,
            artifacts = allArtifacts,
            isError = true
        )
    }
}

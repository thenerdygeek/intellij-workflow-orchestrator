package com.workflow.orchestrator.agent.runtime

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.ChatMessage
import com.workflow.orchestrator.agent.api.dto.ToolDefinition
import com.workflow.orchestrator.agent.brain.LlmBrain
import com.workflow.orchestrator.agent.context.ContextManager
import com.workflow.orchestrator.agent.context.TokenEstimator
import com.workflow.orchestrator.agent.orchestrator.AgentProgress
import com.workflow.orchestrator.agent.orchestrator.ToolCallInfo
import com.workflow.orchestrator.agent.security.CredentialRedactor
import com.workflow.orchestrator.agent.security.OutputValidator
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import kotlinx.coroutines.delay
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
        val artifacts: List<String>,
        val snapshotRef: String? = null
    ) : SingleAgentResult()

    /** Task failed with an error. */
    data class Failed(
        val error: String,
        val tokensUsed: Int,
        val snapshotRef: String? = null
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
 * - [LoopGuard] for loop detection, error nudges, and auto-verification
 * - Nudge injection (NUDGE, STRONG_NUDGE) and budget termination instead of escalation
 * - Graceful retry on rate limits (429) and context length exceeded
 * - [OutputValidator] on final content
 * - Progress callbacks for each iteration
 *
 * Max iterations: 50 (higher than WorkerSession's 10, since this handles full tasks).
 */
class SingleAgentSession(
    private val maxIterations: Int = 50
) {
    companion object {
        private val LOG = Logger.getInstance(SingleAgentSession::class.java)
        private val json = Json { ignoreUnknownKeys = true }
        private const val MAX_RATE_LIMIT_RETRIES = 3
        private val RATE_LIMIT_BACKOFF_MS = longArrayOf(2000, 4000, 8000)

        /** Core tools kept during context reduction (when context_length_exceeded). */
        val CORE_TOOL_NAMES = setOf("read_file", "edit_file", "search_code", "run_command", "diagnostics", "delegate_task")
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
     * @param systemPrompt The assembled system prompt (from PromptAssembler)
     * @param maxOutputTokens Maximum output tokens for LLM responses
     * @param approvalGate Optional gate for risk-based approval of tool actions
     * @param eventLog Optional structured event log for audit trail
     * @param onProgress Callback for progress updates
     * @param onStreamChunk Callback for streaming LLM output tokens (for real-time UI)
     * @return [SingleAgentResult] — completed or failed
     */
    suspend fun execute(
        task: String,
        tools: Map<String, AgentTool>,
        toolDefinitions: List<ToolDefinition>,
        brain: LlmBrain,
        contextManager: ContextManager,
        project: Project,
        systemPrompt: String? = null,
        maxOutputTokens: Int? = null,
        approvalGate: ApprovalGate? = null,
        eventLog: AgentEventLog? = null,
        sessionTrace: SessionTrace? = null,
        onProgress: (AgentProgress) -> Unit = {},
        onStreamChunk: (String) -> Unit = {}
    ): SingleAgentResult {
        LOG.info("SingleAgentSession: starting with ${tools.size} tools for task: ${task.take(100)}")
        eventLog?.log(AgentEventType.SESSION_STARTED, "Task: ${task.take(200)}, tools: ${tools.size}")

        val effectiveBudget = contextManager.remainingBudget() + contextManager.currentTokens
        val budgetEnforcer = BudgetEnforcer(contextManager, effectiveBudget)

        sessionTrace?.sessionStarted(task, tools.size, effectiveBudget - contextManager.remainingBudget(), effectiveBudget)

        // Only add system prompt if explicitly provided (first message).
        // On multi-turn, systemPrompt is null — already in context from session.initialize().
        if (systemPrompt != null) {
            contextManager.addMessage(ChatMessage(role = "system", content = systemPrompt))
        }
        contextManager.addMessage(ChatMessage(role = "user", content = task))

        // Initialize LoopGuard for loop detection and auto-verification
        val loopGuard = LoopGuard()

        var totalTokensUsed = 0
        val allArtifacts = mutableListOf<String>()
        val editedFiles = mutableListOf<String>()

        // Track current tool definitions (may be reduced on context_length_exceeded)
        var activeToolDefs = toolDefinitions
        var activeTools = tools
        var verificationPending = false

        for (iteration in 1..maxIterations) {
            LOG.info("SingleAgentSession: iteration $iteration/$maxIterations")
            sessionTrace?.iterationStarted(iteration, contextManager.currentTokens, budgetEnforcer.utilizationPercent())
            onProgress(AgentProgress(
                step = "Thinking... (iteration $iteration)",
                tokensUsed = totalTokensUsed
            ))

            // Check for pending tool activations from request_tools
            try {
                val agentService = com.workflow.orchestrator.agent.AgentService.getInstance(project)
                val pending = mutableSetOf<String>()
                while (agentService.pendingToolActivations.isNotEmpty()) {
                    agentService.pendingToolActivations.poll()?.let { pending.add(it) }
                }
                if (pending.isNotEmpty()) {
                    // Expand active tools with the requested ones
                    val allRegisteredTools = agentService.toolRegistry.allTools().associateBy { it.name }
                    for (name in pending) {
                        if (name !in activeTools) {
                            val tool = allRegisteredTools[name]
                            if (tool != null) {
                                activeTools = activeTools + (name to tool)
                                activeToolDefs = activeToolDefs + tool.toToolDefinition()
                            }
                        }
                    }
                    LOG.info("SingleAgentSession: expanded tool set with ${pending.size} tools from request_tools: $pending")
                }
            } catch (e: Exception) { LOG.warn("Failed to expand pending tool activations", e) }

            // Budget check before each LLM call
            val budgetStatus = budgetEnforcer.check()
            when (budgetStatus) {
                BudgetEnforcer.BudgetStatus.TERMINATE -> {
                    LOG.warn("SingleAgentSession: budget exhausted at iteration $iteration (${budgetEnforcer.utilizationPercent()}%)")
                    eventLog?.log(AgentEventType.SESSION_FAILED, "Budget terminated at ${budgetEnforcer.utilizationPercent()}%")
                    sessionTrace?.sessionFailed("Budget terminated at ${budgetEnforcer.utilizationPercent()}%", totalTokensUsed, iteration)
                    return SingleAgentResult.Failed(
                        error = "Context budget exhausted at ${budgetEnforcer.utilizationPercent()}%. Please start a new conversation for remaining work.",
                        tokensUsed = totalTokensUsed
                    )
                }
                BudgetEnforcer.BudgetStatus.STRONG_NUDGE -> {
                    LOG.warn("SingleAgentSession: strong nudge at iteration $iteration (${budgetEnforcer.utilizationPercent()}%)")
                    // Note: addMessage() auto-triggers truncation compression if tokens exceed tMax
                    contextManager.addMessage(ChatMessage(
                        role = "system",
                        content = "WARNING: You have used ${budgetEnforcer.utilizationPercent()}% of your context budget. You MUST use delegate_task for any remaining task touching 2+ files. Single-file edits are still allowed directly."
                    ))
                }
                BudgetEnforcer.BudgetStatus.NUDGE -> {
                    LOG.info("SingleAgentSession: nudge at iteration $iteration (${budgetEnforcer.utilizationPercent()}%)")
                    contextManager.addMessage(ChatMessage(
                        role = "system",
                        content = "Context at ${budgetEnforcer.utilizationPercent()}%. Prefer delegate_task for remaining multi-file work — each worker gets a fresh context window."
                    ))
                }
                BudgetEnforcer.BudgetStatus.COMPRESS -> {
                    LOG.info("SingleAgentSession: triggering LLM compression at iteration $iteration")
                    eventLog?.log(AgentEventType.COMPRESSION_TRIGGERED, "At iteration $iteration, ${budgetEnforcer.utilizationPercent()}% used")
                    val tokensBefore = contextManager.currentTokens
                    val messagesBefore = contextManager.messageCount
                    // Use LLM-powered compression when brain is available
                    contextManager.compressWithLlm(brain)
                    sessionTrace?.compressionTriggered("budget_enforcer", tokensBefore, contextManager.currentTokens, messagesBefore - contextManager.messageCount)
                }
                BudgetEnforcer.BudgetStatus.OK -> { /* proceed */ }
            }

            // Ask user to confirm continuing after iteration 25
            if (iteration == 25) {
                LOG.info("SingleAgentSession: reached iteration 25, continuing (budget: ${budgetEnforcer.utilizationPercent()}%)")
                onProgress(AgentProgress(
                    step = "Agent has been working for 25 iterations. Still making progress...",
                    tokensUsed = totalTokensUsed
                ))
            }

            val messages = contextManager.getMessages()
            val toolDefsForCall = if (activeTools.isNotEmpty()) activeToolDefs else null

            // LLM call with retry logic for rate limits and context length exceeded
            val result = callLlmWithRetry(
                brain, messages, toolDefsForCall, maxOutputTokens,
                onStreamChunk, activeToolDefs, activeTools, eventLog
            )

            // Handle tool reduction if context exceeded
            if (result is LlmCallResult.ContextExceededRetry) {
                activeToolDefs = result.reducedToolDefs
                activeTools = result.reducedTools
                eventLog?.log(AgentEventType.CONTEXT_EXCEEDED_RETRY, "Reduced to ${activeTools.size} core tools")
                // Also compress conversation history for maximum token savings
                contextManager.compress()
                // Re-fetch messages after compression
                val compressedMessages = contextManager.getMessages()
                // Retry with reduced tools and compressed context
                val retryResult = callLlmWithRetry(
                    brain, compressedMessages, activeToolDefs, maxOutputTokens,
                    onStreamChunk, activeToolDefs, activeTools, eventLog
                )
                if (retryResult !is LlmCallResult.Success) {
                    val errorMsg = when (retryResult) {
                        is LlmCallResult.Failed -> retryResult.error
                        else -> "Context length exceeded even after tool reduction"
                    }
                    eventLog?.log(AgentEventType.SESSION_FAILED, errorMsg)
                    return SingleAgentResult.Failed(error = errorMsg, tokensUsed = totalTokensUsed)
                }
                // Process the successful retry below
                return processLlmSuccess(
                    retryResult as LlmCallResult.Success, iteration, totalTokensUsed, allArtifacts,
                    editedFiles, activeTools, contextManager, project, approvalGate, loopGuard,
                    budgetEnforcer, brain, activeToolDefs, maxOutputTokens, onProgress,
                    onStreamChunk, eventLog, sessionTrace, maxIterations
                ) ?: continue
            }

            when (result) {
                is LlmCallResult.Success -> {
                    val sessionResult = processLlmSuccess(
                        result, iteration, totalTokensUsed, allArtifacts,
                        editedFiles, activeTools, contextManager, project, approvalGate, loopGuard,
                        budgetEnforcer, brain, activeToolDefs, maxOutputTokens, onProgress,
                        onStreamChunk, eventLog, sessionTrace, maxIterations
                    )
                    if (sessionResult != null) return sessionResult
                    totalTokensUsed = result.totalTokensSoFar
                }
                is LlmCallResult.Failed -> {
                    eventLog?.log(AgentEventType.SESSION_FAILED, result.error)
                    sessionTrace?.dumpConversationState(contextManager.getMessages(), "llm_call_failed: ${result.error}")
                    sessionTrace?.sessionFailed(result.error, totalTokensUsed, iteration)
                    return SingleAgentResult.Failed(
                        error = "LLM call failed: ${result.error}",
                        tokensUsed = totalTokensUsed
                    )
                }
                is LlmCallResult.ContextExceededRetry -> {
                    // Already handled above
                }
            }
        }

        LOG.warn("SingleAgentSession: reached max iterations ($maxIterations)")
        eventLog?.log(AgentEventType.SESSION_FAILED, "Max iterations ($maxIterations) reached")
        sessionTrace?.dumpConversationState(contextManager.getMessages(), "max_iterations_reached")
        sessionTrace?.sessionFailed("Max iterations ($maxIterations) reached", totalTokensUsed, maxIterations)
        return SingleAgentResult.Failed(
            error = "Reached maximum iterations ($maxIterations) without completing",
            tokensUsed = totalTokensUsed
        )
    }

    /**
     * Process a successful LLM response — handle tool calls, LoopGuard, and final response.
     * Returns a SingleAgentResult if the session should end, or null to continue the loop.
     */
    private suspend fun processLlmSuccess(
        result: LlmCallResult.Success,
        iteration: Int,
        totalTokensUsedBefore: Int,
        allArtifacts: MutableList<String>,
        editedFiles: MutableList<String>,
        tools: Map<String, AgentTool>,
        contextManager: ContextManager,
        project: Project,
        approvalGate: ApprovalGate?,
        loopGuard: LoopGuard,
        budgetEnforcer: BudgetEnforcer,
        brain: LlmBrain,
        toolDefinitions: List<ToolDefinition>,
        maxOutputTokens: Int?,
        onProgress: (AgentProgress) -> Unit,
        onStreamChunk: (String) -> Unit,
        eventLog: AgentEventLog?,
        sessionTrace: SessionTrace?,
        maxIterations: Int
    ): SingleAgentResult? {
        val response = result.response
        val usage = response.usage
        var totalTokensUsed = totalTokensUsedBefore
        if (usage != null) {
            totalTokensUsed += usage.totalTokens
        }
        // Store for caller
        (result as LlmCallResult.Success).totalTokensSoFar = totalTokensUsed

        val choice = response.choices.firstOrNull() ?: return SingleAgentResult.Failed(
            error = "Empty response from LLM", tokensUsed = totalTokensUsed
        )
        val message = choice.message

        // Filter out tool calls with empty/blank names (Sourcegraph streaming can produce these)
        val validToolCalls = message.toolCalls?.filter { it.function.name.isNotBlank() }?.ifEmpty { null }
        val cleanMessage = if (validToolCalls != message.toolCalls) {
            message.copy(toolCalls = validToolCalls)
        } else message
        val toolCalls = cleanMessage.toolCalls

        // Only add assistant message to context if it has content or valid tool calls
        if (!cleanMessage.content.isNullOrBlank() || !toolCalls.isNullOrEmpty()) {
            contextManager.addAssistantMessage(cleanMessage)
        } else {
            LOG.info("SingleAgentSession: skipping empty assistant message (no content, no valid tool calls)")
        }

        // Show assistant's text content in UI (for non-streaming responses,
        // the onStreamChunk callback doesn't fire, so push content here)
        if (!cleanMessage.content.isNullOrBlank()) {
            onStreamChunk(cleanMessage.content!!)
        }

        // Handle truncated responses (output token limit hit).
        // With Sourcegraph's 4K output cap, truncation is common for longer answers.
        // Instead of returning an incomplete answer, ask the LLM to continue.
        if (choice.finishReason == "length" && toolCalls.isNullOrEmpty()) {
            LOG.info("SingleAgentSession: response truncated (finishReason=length), requesting continuation")
            contextManager.addMessage(ChatMessage(
                role = "user",
                content = "Your response was truncated due to the output token limit. " +
                    "If you were about to use a tool, please do so now. " +
                    "If you were providing a final answer, please provide a concise summary instead."
            ))
            return null // continue loop to get complete response
        }

        if (toolCalls.isNullOrEmpty()) {
            // No tool calls — about to return final response
            // LoopGuard: check if verification is needed before completing
            val verificationMsg = loopGuard.beforeCompletion()
            if (verificationMsg != null) {
                contextManager.addMessage(verificationMsg)
                return null // continue loop for verification
            }

            val content = message.content ?: ""

            // Validate output for sensitive data and redact if needed
            val securityIssues = OutputValidator.validate(content)
            val sanitizedContent = if (securityIssues.isNotEmpty()) {
                LOG.warn("SingleAgentSession: output validation flagged: ${securityIssues.joinToString()}")
                CredentialRedactor.redact(content)
            } else content

            val summary = if (sanitizedContent.length > 200) sanitizedContent.take(200) + "..." else sanitizedContent
            LOG.info("SingleAgentSession: completed after $iteration iterations, $totalTokensUsed tokens")

            onProgress(AgentProgress(
                step = "Task completed",
                tokensUsed = totalTokensUsed
            ))

            eventLog?.log(AgentEventType.SESSION_COMPLETED, "Completed after $iteration iterations, $totalTokensUsed tokens")
            sessionTrace?.iterationCompleted(iteration, usage?.promptTokens ?: 0, usage?.completionTokens ?: 0, emptyList(), choice.finishReason)
            sessionTrace?.sessionCompleted(totalTokensUsed, iteration, allArtifacts)

            return SingleAgentResult.Completed(
                content = sanitizedContent,
                summary = summary,
                tokensUsed = totalTokensUsed,
                artifacts = allArtifacts
            )
        }

        // Execute tool calls with approval gate
        val toolResults = mutableListOf<Pair<String, Boolean>>() // (toolCallId, isError) for LoopGuard

        for (toolCall in toolCalls) {
            val toolName = toolCall.function.name
            val tool = tools[toolName]

            if (tool == null) {
                eventLog?.log(AgentEventType.TOOL_FAILED, "Tool not found: $toolName")
                contextManager.addToolResult(
                    toolCallId = toolCall.id,
                    content = "Error: Tool '$toolName' not found. Available tools: ${tools.keys.joinToString(", ")}",
                    summary = "Tool not found: $toolName"
                )
                toolResults.add(toolCall.id to true)
                continue
            }

            // Check approval gate before executing risky tools
            if (approvalGate != null) {
                val riskLevel = ApprovalGate.riskLevelFor(toolName)
                eventLog?.log(AgentEventType.APPROVAL_REQUESTED, "$toolName (risk: $riskLevel)")
                val approval = approvalGate.check(
                    toolName = toolName,
                    description = "$toolName(${toolCall.function.arguments.take(100)})",
                    riskLevel = riskLevel
                )
                when (approval) {
                    is ApprovalResult.Rejected -> {
                        eventLog?.log(AgentEventType.APPROVAL_DENIED, toolName)
                        contextManager.addToolResult(
                            toolCallId = toolCall.id,
                            content = "Tool call rejected by user. The user chose not to allow this action.",
                            summary = "Rejected: $toolName"
                        )
                        toolResults.add(toolCall.id to true)
                        continue
                    }
                    is ApprovalResult.Pending -> {
                        contextManager.addToolResult(
                            toolCallId = toolCall.id,
                            content = "Tool call pending user approval. Waiting for user decision.",
                            summary = "Pending approval: $toolName"
                        )
                        toolResults.add(toolCall.id to true)
                        continue
                    }
                    is ApprovalResult.Approved -> {
                        eventLog?.log(AgentEventType.APPROVAL_GRANTED, toolName)
                    }
                }
            }

            // Emit pre-execution progress so users see which tool is being called
            // while it runs (fixes blank screen during non-streaming tool calls)
            onProgress(AgentProgress(
                step = "Calling tool: $toolName",
                tokensUsed = totalTokensUsed,
                toolCallInfo = ToolCallInfo(
                    toolName = toolName,
                    args = toolCall.function.arguments.take(200),
                    isError = false
                )
            ))

            val toolStartMs = System.currentTimeMillis()
            try {
                eventLog?.log(AgentEventType.TOOL_CALLED, "$toolName(${toolCall.function.arguments.take(100)})")
                val params = json.decodeFromString<JsonObject>(toolCall.function.arguments)
                val toolResult = tool.execute(params, project)
                val toolDurationMs = System.currentTimeMillis() - toolStartMs

                contextManager.addToolResult(
                    toolCallId = toolCall.id,
                    content = toolResult.content,
                    summary = toolResult.summary
                )

                allArtifacts.addAll(toolResult.artifacts)
                toolResults.add(toolCall.id to toolResult.isError)

                // Track edited files for LoopGuard auto-verification
                if (toolName == "edit_file" && !toolResult.isError) {
                    editedFiles.addAll(toolResult.artifacts)
                    if (toolResult.content.contains("Edit rejected: syntax errors")) {
                        eventLog?.log(AgentEventType.EDIT_REJECTED_SYNTAX, toolResult.artifacts.firstOrNull() ?: "unknown")
                    } else {
                        eventLog?.log(AgentEventType.EDIT_APPLIED, toolResult.artifacts.firstOrNull() ?: "unknown")
                    }
                }

                if (toolResult.isError) {
                    eventLog?.log(AgentEventType.TOOL_FAILED, "$toolName: ${toolResult.summary}")
                    sessionTrace?.toolExecuted(toolName, toolDurationMs, toolResult.tokenEstimate, true, toolResult.summary)
                } else {
                    eventLog?.log(AgentEventType.TOOL_SUCCEEDED, toolName)
                    sessionTrace?.toolExecuted(toolName, toolDurationMs, toolResult.tokenEstimate, false)
                }

                // Build rich tool call info for the UI
                val editInfo = if (toolName == "edit_file" && !toolResult.isError) {
                    val argsObj = params
                    ToolCallInfo(
                        toolName = toolName,
                        args = toolCall.function.arguments.take(200),
                        result = toolResult.summary,
                        durationMs = toolDurationMs,
                        isError = toolResult.isError,
                        editFilePath = argsObj["path"]?.toString()?.removeSurrounding("\""),
                        editOldText = argsObj["old_string"]?.toString()?.removeSurrounding("\"")?.take(500),
                        editNewText = argsObj["new_string"]?.toString()?.removeSurrounding("\"")?.take(500)
                    )
                } else {
                    ToolCallInfo(
                        toolName = toolName,
                        args = toolCall.function.arguments.take(200),
                        result = toolResult.summary,
                        durationMs = toolDurationMs,
                        isError = toolResult.isError
                    )
                }

                onProgress(AgentProgress(
                    step = "Used tool: $toolName",
                    tokensUsed = totalTokensUsed,
                    toolCallInfo = editInfo
                ))
            } catch (e: Exception) {
                val toolDurationMs = System.currentTimeMillis() - toolStartMs
                LOG.warn("SingleAgentSession: tool '$toolName' failed", e)
                eventLog?.log(AgentEventType.TOOL_FAILED, "$toolName: ${e.message}")
                sessionTrace?.toolExecuted(toolName, toolDurationMs, 0, true, e.message)
                contextManager.addToolResult(
                    toolCallId = toolCall.id,
                    content = "Error executing tool '$toolName': ${e.message}",
                    summary = "Tool error: $toolName"
                )
                toolResults.add(toolCall.id to true)
            }
        }

        // Trace: record iteration completion with tool list
        val toolNames = toolCalls.map { it.function.name }
        sessionTrace?.iterationCompleted(iteration, usage?.promptTokens ?: 0, usage?.completionTokens ?: 0, toolNames, choice.finishReason)

        // LoopGuard: check for loops, error nudges, instruction-fade reminders
        val loopGuardMessages = loopGuard.afterIteration(toolCalls, toolResults, editedFiles.toList())
        for (msg in loopGuardMessages) {
            contextManager.addMessage(msg)
            if (msg.content?.contains("same arguments") == true) {
                eventLog?.log(AgentEventType.LOOP_DETECTED, msg.content ?: "")
            }
        }

        return null // continue loop
    }

    /**
     * Call the LLM with retry logic for rate limits and context length exceeded.
     */
    private suspend fun callLlmWithRetry(
        brain: LlmBrain,
        messages: List<ChatMessage>,
        toolDefs: List<ToolDefinition>?,
        maxOutputTokens: Int?,
        onStreamChunk: (String) -> Unit,
        allToolDefs: List<ToolDefinition>,
        allTools: Map<String, AgentTool>,
        eventLog: AgentEventLog?
    ): LlmCallResult {
        var lastError: String? = null

        for (attempt in 0..MAX_RATE_LIMIT_RETRIES) {
            if (attempt > 0) {
                val backoffMs = RATE_LIMIT_BACKOFF_MS.getOrElse(attempt - 1) { 8000L }
                LOG.info("SingleAgentSession: rate limit retry $attempt, waiting ${backoffMs}ms")
                eventLog?.log(AgentEventType.RATE_LIMITED_RETRY, "Attempt $attempt, backoff ${backoffMs}ms")
                delay(backoffMs)
            }

            // Use non-streaming when tools are present — Sourcegraph's SSE may not
            // properly relay tool_calls in streaming mode (produces empty tool names).
            // Use streaming only for text responses (no tools or after tool reduction).
            val hasTools = !toolDefs.isNullOrEmpty()
            val result = if (hasTools) {
                // Non-streaming: reliable tool call parsing
                brain.chat(messages, toolDefs, maxOutputTokens)
            } else {
                // Streaming: better UX for text-only responses
                try {
                    brain.chatStream(messages, toolDefs, maxOutputTokens) { chunk ->
                        chunk.choices.firstOrNull()?.delta?.content?.let { delta ->
                            onStreamChunk(delta)
                        }
                    }
                } catch (_: NotImplementedError) {
                    brain.chat(messages, toolDefs, maxOutputTokens)
                }
            }

            when (result) {
                is ApiResult.Success -> {
                    return LlmCallResult.Success(result.data)
                }
                is ApiResult.Error -> {
                    when (result.type) {
                        ErrorType.RATE_LIMITED -> {
                            lastError = result.message
                            continue // retry with backoff
                        }
                        ErrorType.CONTEXT_LENGTH_EXCEEDED -> {
                            // Reduce tools to core set and retry once
                            val reducedTools = allTools.filterKeys { it in CORE_TOOL_NAMES }
                            val reducedDefs = allToolDefs.filter { it.function.name in CORE_TOOL_NAMES }
                            return LlmCallResult.ContextExceededRetry(reducedDefs, reducedTools)
                        }
                        else -> {
                            // Also detect context_length from error message for APIs that don't use specific error type
                            if (result.message.contains("context_length", ignoreCase = true)) {
                                val reducedTools = allTools.filterKeys { it in CORE_TOOL_NAMES }
                                val reducedDefs = allToolDefs.filter { it.function.name in CORE_TOOL_NAMES }
                                return LlmCallResult.ContextExceededRetry(reducedDefs, reducedTools)
                            }
                            return LlmCallResult.Failed(result.message)
                        }
                    }
                }
            }
        }

        return LlmCallResult.Failed(lastError ?: "Rate limited after $MAX_RATE_LIMIT_RETRIES retries")
    }

    /** Internal result type for LLM calls with retry. */
    private sealed class LlmCallResult {
        class Success(
            val response: com.workflow.orchestrator.agent.api.dto.ChatCompletionResponse,
            var totalTokensSoFar: Int = 0
        ) : LlmCallResult()
        data class Failed(val error: String) : LlmCallResult()
        data class ContextExceededRetry(
            val reducedToolDefs: List<ToolDefinition>,
            val reducedTools: Map<String, AgentTool>
        ) : LlmCallResult()
    }

    /**
     * Fallback system prompt when no PromptAssembler prompt is provided.
     * Used for backward compatibility in tests and when orchestrator doesn't provide one.
     */
    private fun buildFallbackSystemPrompt(): String {
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
            - If you call the same tool 3 times with the same arguments, try a different approach.
            </rules>

            <output>
            When you've completed the task, provide a clear summary of what you did,
            what files were changed, and any issues encountered.
            </output>
        """.trimIndent()
    }

}

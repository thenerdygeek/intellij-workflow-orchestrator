package com.workflow.orchestrator.agent.loop

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.ToolCall
import com.workflow.orchestrator.agent.api.dto.ToolDefinition
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.truncateOutput
import com.workflow.orchestrator.core.ai.LlmBrain
import com.workflow.orchestrator.core.ai.dto.ChatMessage
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Core ReAct loop: call LLM -> execute tools -> repeat.
 *
 * Follows the Codex CLI + Cline pattern:
 * - Exit ONLY via `attempt_completion` tool (isCompletion=true).
 *   "No tool calls" is NOT completion — the model must explicitly complete.
 * - Empty responses get continuation prompts (fail after 3 consecutive).
 * - Text-only responses get a nudge to use tools or attempt_completion.
 * - Compaction runs when context utilization exceeds threshold.
 * - Loop detection (from Cline): warns at 3, fails at 5 identical consecutive tool calls.
 * - Context overflow detection: catches API errors for context exceeded, compacts + retries.
 */
/**
 * Core ReAct loop: call LLM -> execute tools -> repeat.
 *
 * Checkpoint callback (ported from Cline's message-state.ts):
 * Cline calls saveApiConversationHistory after every addToApiConversationHistory,
 * persisting the full conversation to disk after every tool result. We do the same
 * via [onCheckpoint]: after each tool result is added to context, the callback fires
 * to persist state without blocking the loop (caller runs on IO dispatcher).
 *
 * @param onCheckpoint called after every tool result is added to context.
 *   Matches Cline's "persist after every state change" pattern from message-state.ts.
 */
class AgentLoop(
    private val brain: LlmBrain,
    private val tools: Map<String, AgentTool>,
    private val toolDefinitions: List<ToolDefinition>,
    private val contextManager: ContextManager,
    private val project: Project,
    private val onStreamChunk: (String) -> Unit = {},
    private val onToolCall: (ToolCallProgress) -> Unit = {},
    private val maxIterations: Int = 200,
    private val planMode: Boolean = false,
    private val onCheckpoint: (suspend () -> Unit)? = null
) {
    private val cancelled = AtomicBoolean(false)
    private var totalTokensUsed = 0

    /** Loop detector: tracks repeated identical tool calls (from Cline). */
    private val loopDetector = LoopDetector()

    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private val LOG = Logger.getInstance(AgentLoop::class.java)
        private const val MAX_CONSECUTIVE_EMPTIES = 3
        private const val MAX_API_RETRIES = 5
        private const val MAX_CONTEXT_OVERFLOW_RETRIES = 2
        private const val INITIAL_RETRY_DELAY_MS = 1000L
        private const val CONTINUATION_PROMPT =
            "Your previous response was empty. Please use the available tools to take action on the task, or call attempt_completion if you are done."
        private const val TEXT_ONLY_NUDGE =
            "Please use tools to take action, or call attempt_completion if you're done. Do not just describe what you plan to do — take action with tools."
        private const val LOOP_SOFT_WARNING =
            "WARNING: You have called the same tool with identical arguments multiple times in a row. " +
            "This is not making progress. Please try a different approach, use different parameters, " +
            "or call attempt_completion if you believe the task is done."
        private const val LOOP_HARD_FAILURE =
            "CRITICAL: You have called the same tool with identical arguments 5 times consecutively. " +
            "The task cannot make progress this way. Stopping to prevent further token waste."

        /** Tools that mutate state — blocked when plan mode is active. */
        val WRITE_TOOLS = setOf(
            "edit_file", "create_file", "run_command", "revert_file",
            "kill_process", "send_stdin", "format_code", "optimize_imports",
            "refactor_rename"
        )

        /** Error types that are transient and safe to retry. */
        private val RETRYABLE_ERRORS = setOf(
            ErrorType.RATE_LIMITED,
            ErrorType.SERVER_ERROR,
            ErrorType.NETWORK_ERROR,
            ErrorType.TIMEOUT
        )

        /**
         * Pattern matching for context window overflow errors.
         * Ported from Cline's multi-provider pattern matching:
         * looks for 400-class errors with "token"/"context length" in the message.
         */
        private val CONTEXT_OVERFLOW_PATTERNS = listOf(
            Regex("context.{0,20}(length|window|limit)", RegexOption.IGNORE_CASE),
            Regex("(maximum|max).{0,20}(token|context)", RegexOption.IGNORE_CASE),
            Regex("token.{0,20}(limit|exceeded|overflow)", RegexOption.IGNORE_CASE),
            Regex("(input|prompt).{0,20}too.{0,10}(long|large)", RegexOption.IGNORE_CASE)
        )
    }

    /**
     * Run the ReAct loop. Returns when:
     * - A tool returns isCompletion=true (Completed)
     * - An unrecoverable error occurs (Failed)
     * - The loop is cancelled (Cancelled)
     * - Max iterations exceeded (Failed)
     */
    suspend fun run(task: String): LoopResult {
        if (cancelled.get()) {
            return LoopResult.Cancelled(iterations = 0, tokensUsed = 0)
        }

        contextManager.addUserMessage(task)

        var iteration = 0
        var consecutiveEmpties = 0
        var apiRetryCount = 0
        var contextOverflowRetries = 0

        while (!cancelled.get() && iteration < maxIterations) {
            iteration++

            // Stage 0: Compact if needed
            if (contextManager.shouldCompact()) {
                contextManager.compact(brain)
            }

            // Stage 1: Call LLM
            val apiResult = brain.chatStream(
                messages = contextManager.getMessages(),
                tools = toolDefinitions,
                onChunk = { chunk ->
                    chunk.choices.firstOrNull()?.delta?.content?.let { onStreamChunk(it) }
                }
            )

            // Stage 2: Handle API errors with retry for transient failures
            if (apiResult is ApiResult.Error) {
                // Context overflow detection (from Cline)
                // Cline detects context window exceeded errors and auto-truncates + retries
                if (isContextOverflowError(apiResult) && contextOverflowRetries < MAX_CONTEXT_OVERFLOW_RETRIES) {
                    contextOverflowRetries++
                    LOG.warn("[AgentLoop] Context window overflow detected, compacting and retrying ($contextOverflowRetries/$MAX_CONTEXT_OVERFLOW_RETRIES)")
                    // Force aggressive compaction
                    contextManager.compact(brain)
                    iteration-- // Don't count overflow retries as iterations
                    continue
                }

                if (apiResult.type in RETRYABLE_ERRORS && apiRetryCount < MAX_API_RETRIES) {
                    apiRetryCount++
                    val delayMs = INITIAL_RETRY_DELAY_MS * (1L shl (apiRetryCount - 1))
                    LOG.warn("[AgentLoop] Retryable API error (${apiResult.type}), retry $apiRetryCount/$MAX_API_RETRIES after ${delayMs}ms")
                    delay(delayMs)
                    iteration-- // Don't count retries as iterations
                    continue
                }
                return LoopResult.Failed(
                    error = apiResult.message,
                    iterations = iteration,
                    tokensUsed = totalTokensUsed
                )
            }

            val response = (apiResult as ApiResult.Success).data

            // Reset retry counts on successful API call
            apiRetryCount = 0
            contextOverflowRetries = 0

            // Stage 3: Update token tracking
            response.usage?.let { usage ->
                totalTokensUsed += usage.totalTokens
                contextManager.updateTokens(usage.promptTokens)
            }

            val choice = response.choices.firstOrNull() ?: continue
            val assistantMessage = choice.message

            // Stage 3.5: Handle truncated response (finish_reason: length)
            if (choice.finishReason == "length") {
                // Response was truncated — don't try to parse partial tool calls
                contextManager.addAssistantMessage(
                    ChatMessage(role = "assistant", content = assistantMessage.content ?: "")
                )
                contextManager.addUserMessage(
                    "Your response was cut short due to output length limits. " +
                    "Please continue from where you left off, using smaller steps."
                )
                continue
            }

            // Stage 4: Add assistant message to context
            contextManager.addAssistantMessage(assistantMessage)

            val hasToolCalls = !assistantMessage.toolCalls.isNullOrEmpty()
            val hasContent = !assistantMessage.content.isNullOrBlank()

            when {
                // Case A: Tool calls present — execute them
                hasToolCalls -> {
                    consecutiveEmpties = 0
                    val completionResult = executeToolCalls(assistantMessage.toolCalls!!, iteration)
                    if (completionResult != null) return completionResult
                }

                // Case B: Text only — nudge model to use tools
                hasContent -> {
                    consecutiveEmpties = 0
                    contextManager.addUserMessage(TEXT_ONLY_NUDGE)
                }

                // Case C: Empty response — inject continuation, track consecutive count
                else -> {
                    consecutiveEmpties++
                    if (consecutiveEmpties >= MAX_CONSECUTIVE_EMPTIES) {
                        return LoopResult.Failed(
                            error = "Failed after $MAX_CONSECUTIVE_EMPTIES consecutive empty responses from model.",
                            iterations = iteration,
                            tokensUsed = totalTokensUsed
                        )
                    }
                    contextManager.addUserMessage(CONTINUATION_PROMPT)
                }
            }
        }

        if (cancelled.get()) {
            return LoopResult.Cancelled(iterations = iteration, tokensUsed = totalTokensUsed)
        }

        return LoopResult.Failed(
            error = "Exceeded maximum iterations ($maxIterations). The task may be too complex or the model is stuck.",
            iterations = iteration,
            tokensUsed = totalTokensUsed
        )
    }

    /**
     * Cancel the running loop. Safe to call from any thread.
     */
    fun cancel() {
        cancelled.set(true)
        brain.cancelActiveRequest()
    }

    /**
     * Check if an API error indicates context window overflow.
     *
     * Ported from Cline's multi-provider pattern matching:
     * matches 400-class errors with context/token keywords in the message.
     * Also checks for our explicit CONTEXT_LENGTH_EXCEEDED error type.
     */
    internal fun isContextOverflowError(error: ApiResult.Error): Boolean {
        // Explicit error type from our API client
        if (error.type == ErrorType.CONTEXT_LENGTH_EXCEEDED) return true

        // Pattern matching for other providers (Cline's approach)
        if (error.type == ErrorType.VALIDATION_ERROR || error.type == ErrorType.SERVER_ERROR) {
            return CONTEXT_OVERFLOW_PATTERNS.any { it.containsMatchIn(error.message) }
        }

        return false
    }

    /**
     * Execute tool calls from the assistant message.
     * Returns a LoopResult if a completion tool was called, null otherwise.
     *
     * Integrates loop detection (from Cline):
     * - Before each tool execution, check for repeated identical calls
     * - At soft threshold (3): inject warning into context
     * - At hard threshold (5): return Failed result
     */
    private suspend fun executeToolCalls(toolCalls: List<ToolCall>, iteration: Int): LoopResult? {
        for (call in toolCalls) {
            if (cancelled.get()) {
                return LoopResult.Cancelled(iterations = iteration, tokensUsed = totalTokensUsed)
            }

            val toolName = call.function.name
            val toolCallId = call.id
            val startTime = System.currentTimeMillis()

            // Loop detection (from Cline) — check BEFORE executing
            val loopStatus = loopDetector.recordToolCall(toolName, call.function.arguments)
            when (loopStatus) {
                LoopStatus.HARD_LIMIT -> {
                    LOG.warn("[AgentLoop] Hard loop limit reached: '$toolName' called ${loopDetector.currentCount} times consecutively")
                    contextManager.addToolResult(
                        toolCallId = toolCallId,
                        content = LOOP_HARD_FAILURE,
                        isError = true,
                        toolName = toolName
                    )
                    onToolCall(
                        ToolCallProgress(
                            toolName = toolName,
                            args = call.function.arguments,
                            result = LOOP_HARD_FAILURE,
                            durationMs = System.currentTimeMillis() - startTime,
                            isError = true,
                            toolCallId = toolCallId
                        )
                    )
                    return LoopResult.Failed(
                        error = "Loop detected: '$toolName' called ${loopDetector.currentCount} times with identical arguments.",
                        iterations = iteration,
                        tokensUsed = totalTokensUsed
                    )
                }
                LoopStatus.SOFT_WARNING -> {
                    LOG.warn("[AgentLoop] Soft loop warning: '$toolName' called ${loopDetector.currentCount} times consecutively")
                    // Inject warning but continue execution (give the model a chance to self-correct)
                    contextManager.addUserMessage(LOOP_SOFT_WARNING)
                }
                LoopStatus.OK -> { /* no action */ }
            }

            val tool = tools[toolName]
            if (tool == null) {
                // Unknown tool
                val errorMsg = "Unknown tool: '$toolName'. Available tools: ${tools.keys.joinToString(", ")}"
                contextManager.addToolResult(toolCallId = toolCallId, content = errorMsg, isError = true, toolName = toolName)
                onToolCall(
                    ToolCallProgress(
                        toolName = toolName,
                        args = call.function.arguments,
                        result = errorMsg,
                        durationMs = System.currentTimeMillis() - startTime,
                        isError = true,
                        toolCallId = toolCallId
                    )
                )
                continue
            }

            // Plan mode guard: block write tools even if the LLM hallucinates them
            if (planMode && toolName in WRITE_TOOLS) {
                val errorMsg = "Error: '$toolName' is blocked in plan mode. You can only read, search, and analyze code."
                contextManager.addToolResult(toolCallId = toolCallId, content = errorMsg, isError = true, toolName = toolName)
                onToolCall(
                    ToolCallProgress(
                        toolName = toolName,
                        args = call.function.arguments,
                        result = errorMsg,
                        durationMs = System.currentTimeMillis() - startTime,
                        isError = true,
                        toolCallId = toolCallId
                    )
                )
                continue
            }

            // Parse arguments
            val params: JsonObject = try {
                json.decodeFromString<JsonObject>(call.function.arguments)
            } catch (e: Exception) {
                val errorMsg = "Invalid JSON arguments for '$toolName': ${e.message}"
                contextManager.addToolResult(toolCallId = toolCallId, content = errorMsg, isError = true, toolName = toolName)
                onToolCall(
                    ToolCallProgress(
                        toolName = toolName,
                        args = call.function.arguments,
                        result = errorMsg,
                        durationMs = System.currentTimeMillis() - startTime,
                        isError = true,
                        toolCallId = toolCallId
                    )
                )
                continue
            }

            // Fire start callback (empty result, zero duration = RUNNING)
            onToolCall(
                ToolCallProgress(
                    toolName = toolName,
                    args = call.function.arguments,
                    toolCallId = toolCallId
                )
            )

            // Execute tool
            val toolResult = try {
                tool.execute(params, project)
            } catch (e: Exception) {
                val errorMsg = "Tool '$toolName' threw exception: ${e.message}"
                contextManager.addToolResult(toolCallId = toolCallId, content = errorMsg, isError = true, toolName = toolName)
                onToolCall(
                    ToolCallProgress(
                        toolName = toolName,
                        args = call.function.arguments,
                        result = errorMsg,
                        durationMs = System.currentTimeMillis() - startTime,
                        isError = true,
                        toolCallId = toolCallId
                    )
                )
                continue
            }

            val durationMs = System.currentTimeMillis() - startTime
            val truncatedContent = truncateOutput(toolResult.content)

            // Add result to context
            contextManager.addToolResult(
                toolCallId = toolCallId,
                content = truncatedContent,
                isError = toolResult.isError,
                toolName = toolName
            )

            // Checkpoint: persist state after every tool result (Cline's pattern).
            // Cline calls saveApiConversationHistory inside addToApiConversationHistory.
            // We fire the callback here so AgentService can persist asynchronously.
            onCheckpoint?.invoke()

            // Notify callback
            onToolCall(
                ToolCallProgress(
                    toolName = toolName,
                    args = call.function.arguments,
                    result = toolResult.summary,
                    durationMs = durationMs,
                    isError = toolResult.isError,
                    toolCallId = toolCallId
                )
            )

            // Check for completion
            if (toolResult.isCompletion) {
                return LoopResult.Completed(
                    summary = toolResult.content,
                    iterations = iteration,
                    tokensUsed = totalTokensUsed,
                    verifyCommand = toolResult.verifyCommand
                )
            }
        }
        return null
    }
}

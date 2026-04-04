package com.workflow.orchestrator.agent.loop

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.ToolCall
import com.workflow.orchestrator.agent.api.dto.ToolDefinition
import com.workflow.orchestrator.agent.hooks.HookEvent
import com.workflow.orchestrator.agent.hooks.HookManager
import com.workflow.orchestrator.agent.hooks.HookResult
import com.workflow.orchestrator.agent.hooks.HookType
import com.workflow.orchestrator.agent.security.CommandSafetyAnalyzer
import com.workflow.orchestrator.agent.security.CommandRisk
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.truncateOutput
import com.workflow.orchestrator.core.ai.LlmBrain
import com.workflow.orchestrator.core.ai.dto.ChatMessage
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Result of the user's decision when asked to approve a tool execution.
 *
 * Ported from Cline's approval flow: Cline shows an approval card for write
 * operations (edit_file, run_command, etc.) and waits for the user to respond.
 * - APPROVED: proceed with this one execution
 * - DENIED: skip this tool call, report denial to the LLM
 * - ALLOWED_FOR_SESSION: approve this tool for the rest of the session
 */
enum class ApprovalResult { APPROVED, DENIED, ALLOWED_FOR_SESSION }

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
    private val onTaskProgress: (TaskProgress) -> Unit = {},
    private val maxIterations: Int = 200,
    private val planMode: Boolean = false,
    private val onCheckpoint: (suspend () -> Unit)? = null,
    /**
     * Optional provider for dynamic tool definitions. When set, this is called on each
     * iteration to get the current tool definitions (supporting tool_search activation).
     * Falls back to the static [toolDefinitions] list when null.
     */
    private val toolDefinitionProvider: (() -> List<ToolDefinition>)? = null,
    /**
     * Optional provider for resolving tools by name dynamically.
     * Supports deferred tools that aren't in the initial [tools] map.
     * Falls back to the static [tools] map when null.
     */
    private val toolResolver: ((String) -> AgentTool?)? = null,
    /**
     * Optional callback fired after each API call with cumulative token counts.
     * Ported from Cline's cost tracking: Cline tracks tokensIn/tokensOut in HistoryItem
     * and updates the webview after each API response. We fire this callback so the UI
     * can show running totals.
     */
    private val onTokenUpdate: ((inputTokens: Int, outputTokens: Int) -> Unit)? = null,
    /** Max output tokens per LLM call. Default from AgentSettings: 64000. Passed as max_tokens in the API request. */
    private val maxOutputTokens: Int? = null,
    /**
     * Optional callback fired after write operations (edit_file, create_file, etc.)
     * to create a named checkpoint. Ported from Cline's checkpoint reversion pattern:
     * checkpoints are created at meaningful mutation points so the user can revert.
     *
     * @param toolName the tool that triggered the checkpoint
     * @param args the tool arguments (used for description)
     */
    private val onWriteCheckpoint: (suspend (toolName: String, args: String) -> Unit)? = null,
    /**
     * Optional approval gate for write tool executions (ported from Cline's approval flow).
     *
     * When set, the loop suspends before executing write tools (edit_file, create_file,
     * run_command, revert_file) and waits for the user to approve, deny, or allow for
     * the rest of the session. Uses CompletableDeferred under the hood so the coroutine
     * suspends without blocking a thread.
     *
     * When null (e.g. in sub-agents), write tools execute without approval.
     *
     * @param toolName the tool about to execute
     * @param args the raw JSON arguments string
     * @param riskLevel "low", "medium", or "high" risk classification
     * @return the user's decision
     */
    private val approvalGate: (suspend (toolName: String, args: String, riskLevel: String) -> ApprovalResult)? = null,
    /**
     * Optional hook manager for lifecycle extensibility points.
     * Ported from Cline's hook system: dispatches PRE_TOOL_USE and POST_TOOL_USE
     * hooks around each tool execution. Null = hooks disabled (zero overhead).
     *
     * @see <a href="https://github.com/cline/cline/blob/main/src/core/task/tools/utils/ToolHookUtils.ts">Cline ToolHookUtils</a>
     */
    private val hookManager: HookManager? = null,
    /**
     * Session ID passed to hook events for identification.
     */
    private val sessionId: String? = null,
    /**
     * Callback fired when plan_mode_respond produces a plan.
     * The UI uses this to render the plan card with per-step comment buttons.
     * The loop does NOT exit — it continues or waits for user input.
     *
     * @param planText the plan text from the LLM
     * @param needsMoreExploration if true, loop continues immediately (LLM explores more)
     */
    private val onPlanResponse: ((planText: String, needsMoreExploration: Boolean) -> Unit)? = null,
    /**
     * Channel for receiving user input during the loop.
     * Used in plan mode: after the LLM presents a plan (needsMoreExploration=false),
     * the loop suspends here waiting for the user to type a message, add comments,
     * or click approve. This matches Cline's ask() pattern.
     *
     * When null, the loop does not wait for user input (legacy behavior / sub-agent mode).
     */
    val userInputChannel: Channel<String>? = null
) {
    private val cancelled = AtomicBoolean(false)
    private var totalTokensUsed = 0
    /** Cumulative input (prompt) tokens across all API calls in this loop. */
    private var totalInputTokens = 0
    /** Cumulative output (completion) tokens across all API calls in this loop. */
    private var totalOutputTokens = 0

    /** Files modified during this loop run (from tool artifacts). Gap 1+14: file tracking. */
    private val modifiedFiles = mutableSetOf<String>()
    /** Lines added during this loop run (from edit/create diffs). Gap 21: change tracking. */
    private var totalLinesAdded = 0
    /** Lines removed during this loop run (from edit diffs). Gap 21: change tracking. */
    private var totalLinesRemoved = 0

    /** Loop detector: tracks repeated identical tool calls (from Cline). */
    private val loopDetector = LoopDetector()

    /** Tools the user has allowed for the rest of this session (via ALLOWED_FOR_SESSION). */
    private val approvedForSession = mutableSetOf<String>()

    /**
     * Tracks the last tool name called — used to enforce act_mode_respond
     * consecutive-call constraint (ported from Cline: act_mode_respond cannot
     * be called twice in a row).
     */
    private var lastToolName: String? = null

    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private val LOG = Logger.getInstance(AgentLoop::class.java)
        private const val MAX_CONSECUTIVE_EMPTIES = 3
        private const val MAX_API_RETRIES = 5
        private const val MAX_CONTEXT_OVERFLOW_RETRIES = 2
        private const val INITIAL_RETRY_DELAY_MS = 1000L
        /** Cap on retry-after header delay to prevent unreasonable waits (Cline: maxDelay 10s). */
        private const val MAX_RETRY_DELAY_MS = 30_000L
        /**
         * Nudge for text-only responses (no tool calls).
         * Ported from Cline's formatResponse.noToolsUsed() in responses.ts.
         */
        private const val TEXT_ONLY_NUDGE =
            "[ERROR] You did not use a tool in your previous response! Please retry with a tool use.\n\n" +
            "# Next Steps\n\n" +
            "If you have completed the user's task, use the attempt_completion tool.\n" +
            "If you require additional information from the user, use the ask_followup_question tool.\n" +
            "If you want to respond conversationally, use the act_mode_respond tool.\n" +
            "Otherwise, if you have not completed the task and do not need additional information, " +
            "then proceed with the next step of the task.\n" +
            "(This is an automated message, so do not respond to it conversationally.)"

        /**
         * Message for empty responses (no text, no tools).
         * Ported from Cline: empty responses are treated as PROVIDER ERRORS,
         * separate from text-only (model mistakes).
         */
        private const val EMPTY_RESPONSE_ERROR =
            "Invalid API Response: The provider returned an empty or unparsable response. " +
            "This is a provider-side issue where the model failed to generate valid output. " +
            "Please retry — if the problem persists, check the model or provider configuration."
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

        /** Subset of write tools that require user approval via the approval gate. */
        val APPROVAL_TOOLS = setOf("edit_file", "create_file", "run_command", "revert_file")

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

        /**
         * The parameter name used by the LLM to communicate task progress.
         * Matches Cline's `task_progress` parameter in tool calls.
         */
        private const val TASK_PROGRESS_PARAM = "task_progress"
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
            return LoopResult.Cancelled(iterations = 0, tokensUsed = 0, inputTokens = 0, outputTokens = 0)
        }

        LOG.info("[Loop] Starting task (maxIterations=$maxIterations, planMode=$planMode)")
        contextManager.addUserMessage(task)

        var iteration = 0
        var consecutiveEmpties = 0
        var consecutiveMistakes = 0  // Cline: consecutiveMistakeCount — tracks text-only (no tool) responses
        val maxConsecutiveMistakes = 3  // Cline: configurable via settings. At max, asks user for feedback.
        var apiRetryCount = 0
        var contextOverflowRetries = 0

        while (!cancelled.get() && iteration < maxIterations) {
            iteration++
            LOG.info("[Loop] Iteration $iteration -- ${contextManager.messageCount()} messages, ${"%.1f".format(contextManager.utilizationPercent())}% context")

            // Stage 0: Compact if needed
            if (contextManager.shouldCompact()) {
                LOG.info("[Loop] Context compaction triggered at ${"%.1f".format(contextManager.utilizationPercent())}%")
                contextManager.compact(brain)
            }

            // Stage 1: Call LLM (use dynamic definitions if tool_search has loaded new tools)
            val currentToolDefs = toolDefinitionProvider?.invoke() ?: toolDefinitions
            val apiResult = brain.chatStream(
                messages = contextManager.getMessages(),
                tools = currentToolDefs,
                maxTokens = maxOutputTokens,
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
                    LOG.warn("[Loop] Context overflow detected, compacting and retrying ($contextOverflowRetries/$MAX_CONTEXT_OVERFLOW_RETRIES)")
                    // Force aggressive compaction
                    contextManager.compact(brain)
                    iteration-- // Don't count overflow retries as iterations
                    continue
                }

                if (apiResult.type in RETRYABLE_ERRORS && apiRetryCount < MAX_API_RETRIES) {
                    apiRetryCount++
                    // Use server-provided retry delay if available (ported from Cline's retry.ts),
                    // otherwise fall back to exponential backoff
                    val delayMs = apiResult.retryAfterMs
                        ?.coerceAtMost(MAX_RETRY_DELAY_MS)
                        ?: (INITIAL_RETRY_DELAY_MS * (1L shl (apiRetryCount - 1)))
                    val delaySource = if (apiResult.retryAfterMs != null) "retry-after header" else "exponential backoff"
                    LOG.warn("[Loop] API retry $apiRetryCount/$MAX_API_RETRIES (${apiResult.type}, delay=${delayMs}ms, source=$delaySource)")
                    delay(delayMs)
                    iteration-- // Don't count retries as iterations
                    continue
                }
                LOG.warn("[Loop] Task failed after $iteration iterations: ${apiResult.message.take(200)}")
                return LoopResult.Failed(
                    error = apiResult.message,
                    iterations = iteration,
                    tokensUsed = totalTokensUsed,
                    inputTokens = totalInputTokens,
                    outputTokens = totalOutputTokens,
                    filesModified = filesModifiedList(),
                    linesAdded = totalLinesAdded,
                    linesRemoved = totalLinesRemoved
                )
            }

            val response = (apiResult as ApiResult.Success).data

            // Reset retry counts on successful API call
            apiRetryCount = 0
            contextOverflowRetries = 0

            // Stage 3: Update token tracking (ported from Cline's cost tracking)
            // Cline accumulates tokensIn/tokensOut in HistoryItem after each API call.
            response.usage?.let { usage ->
                totalTokensUsed += usage.totalTokens
                totalInputTokens += usage.promptTokens
                totalOutputTokens += usage.completionTokens
                contextManager.updateTokens(usage.promptTokens)
                onTokenUpdate?.invoke(totalInputTokens, totalOutputTokens)
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
                    consecutiveMistakes = 0  // Tool use resets mistake count
                    val completionResult = executeToolCalls(assistantMessage.toolCalls!!, iteration)
                    if (completionResult != null) return completionResult
                }

                // Case B: Text only (no tool calls) — Cline pattern: inject nudge, increment mistake count
                // Ported from Cline index.ts line 3201-3207: noToolsUsed + consecutiveMistakeCount++
                hasContent -> {
                    consecutiveEmpties = 0
                    consecutiveMistakes++
                    LOG.info("[Loop] Text-only response (no tool calls) — mistake $consecutiveMistakes/$maxConsecutiveMistakes")

                    if (planMode && userInputChannel != null) {
                        // In plan mode, text-only responses are conversational turns.
                        val userMessage = userInputChannel.receive()
                        contextManager.addUserMessage(userMessage)
                        consecutiveMistakes = 0
                    } else if (consecutiveMistakes >= maxConsecutiveMistakes && userInputChannel != null) {
                        // Cline pattern: at max mistakes, ask user for feedback instead of failing
                        LOG.warn("[Loop] Max consecutive mistakes ($maxConsecutiveMistakes) — waiting for user feedback")
                        val userMessage = userInputChannel.receive()
                        contextManager.addUserMessage(userMessage)
                        consecutiveMistakes = 0
                    } else if (consecutiveMistakes >= maxConsecutiveMistakes) {
                        // No user input channel (sub-agent) — fail
                        return LoopResult.Failed(
                            error = "Agent failed to use tools after $maxConsecutiveMistakes attempts.",
                            iterations = iteration,
                            tokensUsed = totalTokensUsed,
                            inputTokens = totalInputTokens,
                            outputTokens = totalOutputTokens,
                            filesModified = filesModifiedList(),
                            linesAdded = totalLinesAdded,
                            linesRemoved = totalLinesRemoved
                        )
                    } else {
                        // Below max — inject nudge and continue (Cline: noToolsUsed message)
                        contextManager.addUserMessage(TEXT_ONLY_NUDGE)
                    }
                }

                // Case C: Empty response — provider error (Cline treats separately from text-only)
                // Ported from Cline: empty = provider error, NOT a model mistake
                else -> {
                    consecutiveEmpties++
                    LOG.warn("[Loop] Empty response from LLM — provider error (attempt $consecutiveEmpties/$MAX_CONSECUTIVE_EMPTIES)")
                    if (consecutiveEmpties >= MAX_CONSECUTIVE_EMPTIES) {
                        return LoopResult.Failed(
                            error = "Provider returned $MAX_CONSECUTIVE_EMPTIES consecutive empty responses. Check model/provider configuration.",
                            iterations = iteration,
                            tokensUsed = totalTokensUsed,
                            inputTokens = totalInputTokens,
                            outputTokens = totalOutputTokens,
                            filesModified = filesModifiedList(),
                            linesAdded = totalLinesAdded,
                            linesRemoved = totalLinesRemoved
                        )
                    }
                    contextManager.addUserMessage(EMPTY_RESPONSE_ERROR)
                }
            }
        }

        if (cancelled.get()) {
            LOG.info("[Loop] Task cancelled at iteration $iteration")
            return LoopResult.Cancelled(
                iterations = iteration,
                tokensUsed = totalTokensUsed,
                inputTokens = totalInputTokens,
                outputTokens = totalOutputTokens,
                filesModified = filesModifiedList(),
                linesAdded = totalLinesAdded,
                linesRemoved = totalLinesRemoved
            )
        }

        LOG.warn("[Loop] Task failed after $iteration iterations: exceeded maximum iterations ($maxIterations)")
        return LoopResult.Failed(
            error = "Exceeded maximum iterations ($maxIterations). The task may be too complex or the model is stuck.",
            iterations = iteration,
            tokensUsed = totalTokensUsed,
            inputTokens = totalInputTokens,
            outputTokens = totalOutputTokens,
            filesModified = filesModifiedList(),
            linesAdded = totalLinesAdded,
            linesRemoved = totalLinesRemoved
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
                return LoopResult.Cancelled(
                    iterations = iteration,
                    tokensUsed = totalTokensUsed,
                    inputTokens = totalInputTokens,
                    outputTokens = totalOutputTokens,
                    filesModified = filesModifiedList(),
                    linesAdded = totalLinesAdded,
                    linesRemoved = totalLinesRemoved
                )
            }

            val toolName = call.function.name
            val toolCallId = call.id
            val startTime = System.currentTimeMillis()

            // Loop detection (from Cline) — check BEFORE executing
            val loopStatus = loopDetector.recordToolCall(toolName, call.function.arguments)
            when (loopStatus) {
                LoopStatus.HARD_LIMIT -> {
                    LOG.warn("[Loop] Hard loop limit reached: '$toolName' called ${loopDetector.currentCount} times consecutively")
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
                        tokensUsed = totalTokensUsed,
                        inputTokens = totalInputTokens,
                        outputTokens = totalOutputTokens,
                        filesModified = filesModifiedList(),
                        linesAdded = totalLinesAdded,
                        linesRemoved = totalLinesRemoved
                    )
                }
                LoopStatus.SOFT_WARNING -> {
                    LOG.warn("[Loop] Soft loop warning: '$toolName' called ${loopDetector.currentCount} times consecutively")
                    // Inject warning but continue execution (give the model a chance to self-correct)
                    contextManager.addUserMessage(LOOP_SOFT_WARNING)
                }
                LoopStatus.OK -> { /* no action */ }
            }

            val tool = toolResolver?.invoke(toolName) ?: tools[toolName]
            if (tool == null) {
                // Unknown tool
                val allToolNames = if (toolResolver != null) "use tool_search to find tools" else tools.keys.joinToString(", ")
                val errorMsg = "Unknown tool: '$toolName'. Available tools: $allToolNames"
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

            // act_mode_respond consecutive-call guard (ported from Cline).
            // Cline: "This tool cannot be called consecutively — each use must be
            // followed by a different tool call or completion."
            if (toolName == "act_mode_respond" && lastToolName == "act_mode_respond") {
                val errorMsg = "Error: act_mode_respond cannot be called consecutively. " +
                    "Use a different tool or call attempt_completion."
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

            // Approval gate (ported from Cline's approval flow).
            // Write tools require user approval unless already allowed for this session.
            // The gate suspends the coroutine (not blocking a thread) until the user responds.
            if (toolName in APPROVAL_TOOLS && approvalGate != null && toolName !in approvedForSession) {
                val riskLevel = assessRisk(toolName, call.function.arguments)
                val result = approvalGate.invoke(toolName, call.function.arguments, riskLevel)
                when (result) {
                    ApprovalResult.DENIED -> {
                        val errorMsg = "Tool execution denied by user."
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
                    ApprovalResult.ALLOWED_FOR_SESSION -> approvedForSession.add(toolName)
                    ApprovalResult.APPROVED -> { /* proceed with this single execution */ }
                }
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

            // Extract task_progress from tool call arguments (Cline's FocusChain pattern).
            // In Cline, task_progress is a parameter on every tool call. The LLM includes
            // it when it wants to update the progress checklist. We extract it here,
            // store it in ContextManager, and notify the UI.
            extractTaskProgress(params)

            // PRE_TOOL_USE hook (ported from Cline's ToolHookUtils.runPreToolUseIfEnabled)
            // Runs before each tool execution; can cancel (block) the tool.
            // Cline: "This should be called by tool handlers after approval succeeds
            //  but before the actual tool execution begins."
            if (hookManager != null && hookManager.hasHooks(HookType.PRE_TOOL_USE)) {
                val preHookResult = hookManager.dispatch(
                    HookEvent(
                        type = HookType.PRE_TOOL_USE,
                        data = mapOf(
                            "toolName" to toolName,
                            "arguments" to call.function.arguments,
                            "iteration" to iteration,
                            "sessionId" to sessionId
                        )
                    )
                )
                if (preHookResult is HookResult.Cancel) {
                    val errorMsg = "Tool '$toolName' blocked by PreToolUse hook: ${preHookResult.reason}"
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
            }

            // Fire start callback (empty result, zero duration = RUNNING)
            val argsSummary = call.function.arguments.take(200)
            LOG.info("[Loop] Executing tool: $toolName ($argsSummary)")
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
                LOG.warn("[Loop] Tool $toolName failed: ${errorMsg.take(200)}")
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

            if (toolResult.isError) {
                LOG.warn("[Loop] Tool $toolName failed: ${toolResult.content.take(200)}")
            } else {
                LOG.info("[Loop] Tool $toolName completed in ${durationMs}ms (OK)")
            }

            // Add result to context
            contextManager.addToolResult(
                toolCallId = toolCallId,
                content = truncatedContent,
                isError = toolResult.isError,
                toolName = toolName
            )

            // POST_TOOL_USE hook (ported from Cline's PostToolUse hook)
            // Observation-only: runs after tool execution, cannot change the result.
            // Cline: fires PostToolUse with tool name, parameters, result, success, durationMs.
            if (hookManager != null && hookManager.hasHooks(HookType.POST_TOOL_USE)) {
                try {
                    hookManager.dispatch(
                        HookEvent(
                            type = HookType.POST_TOOL_USE,
                            data = mapOf(
                                "toolName" to toolName,
                                "arguments" to call.function.arguments,
                                "result" to toolResult.summary,
                                "durationMs" to durationMs,
                                "isError" to toolResult.isError,
                                "sessionId" to sessionId
                            )
                        )
                    )
                } catch (e: Exception) {
                    // POST_TOOL_USE is observation-only; failures are non-fatal
                    LOG.warn("[Loop] POST_TOOL_USE hook failed (non-fatal): ${e.message}")
                }
            }

            // Checkpoint: persist state after every tool result (Cline's pattern).
            // Cline calls saveApiConversationHistory inside addToApiConversationHistory.
            // We fire the callback here so AgentService can persist asynchronously.
            onCheckpoint?.invoke()

            // Gap 1+14: Track modified files from tool artifacts
            if (toolResult.artifacts.isNotEmpty()) {
                modifiedFiles.addAll(toolResult.artifacts)
            }

            // Gap 21: Track line changes from diffs
            if (toolResult.diff != null) {
                countDiffChanges(toolResult.diff)
            }

            // Write checkpoint: after write operations, create a named checkpoint
            // for reversion support (ported from Cline's checkpoint reversion)
            if (!toolResult.isError && toolName in WRITE_TOOLS) {
                onWriteCheckpoint?.invoke(toolName, call.function.arguments)
            }

            // Notify callback (includes editDiff for file change tools — ported from Cline)
            onToolCall(
                ToolCallProgress(
                    toolName = toolName,
                    args = call.function.arguments,
                    result = toolResult.summary,
                    durationMs = durationMs,
                    isError = toolResult.isError,
                    toolCallId = toolCallId,
                    editDiff = toolResult.diff
                )
            )

            // Track last tool name for consecutive-call guards
            lastToolName = toolName

            // Check for completion
            if (toolResult.isCompletion) {
                LOG.info("[Loop] Task completed in $iteration iterations ($totalInputTokens input, $totalOutputTokens output tokens)")
                return LoopResult.Completed(
                    summary = toolResult.content,
                    iterations = iteration,
                    tokensUsed = totalTokensUsed,
                    verifyCommand = toolResult.verifyCommand,
                    inputTokens = totalInputTokens,
                    outputTokens = totalOutputTokens,
                    filesModified = filesModifiedList(),
                    linesAdded = totalLinesAdded,
                    linesRemoved = totalLinesRemoved
                )
            }

            // Check for session handoff (new_task tool — ported from Cline)
            if (toolResult.isSessionHandoff) {
                return LoopResult.SessionHandoff(
                    context = toolResult.handoffContext ?: toolResult.content,
                    iterations = iteration,
                    tokensUsed = totalTokensUsed,
                    inputTokens = totalInputTokens,
                    outputTokens = totalOutputTokens,
                    filesModified = filesModifiedList(),
                    linesAdded = totalLinesAdded,
                    linesRemoved = totalLinesRemoved
                )
            }

            // Check for plan response (plan_mode_respond tool)
            // Matches Cline's plan mode: the loop NEVER exits for plan presentation.
            // - Notify UI via callback so the plan card can be rendered
            // - If needs_more_exploration=true, loop continues immediately (LLM explores more)
            // - If needs_more_exploration=false, wait for user input before next LLM call
            if (toolResult.isPlanResponse) {
                LOG.info("[Loop] Plan presented (needsMoreExploration=${toolResult.needsMoreExploration})")
                onPlanResponse?.invoke(toolResult.content, toolResult.needsMoreExploration)

                if (!toolResult.needsMoreExploration && userInputChannel != null) {
                    // Wait for user input (matches Cline's ask() pattern).
                    // The user can: type in chat, add step comments, or click approve.
                    // Each sends a message into the channel, which resumes the loop.
                    val userMessage = userInputChannel.receive()
                    contextManager.addUserMessage(userMessage)
                    // Continue the loop — LLM will see the user's message and respond
                }
                // needs_more_exploration=true OR no channel: loop continues immediately
            }

            // Store active skill in ContextManager for compaction survival
            // (ported from Cline: skill content is re-injected after compaction)
            if (toolResult.isSkillActivation && toolResult.activatedSkillContent != null) {
                contextManager.setActiveSkill(toolResult.activatedSkillContent)
            }
        }
        return null
    }

    /** Build the common tracking fields for LoopResult. */
    private fun filesModifiedList(): List<String> = modifiedFiles.toList()

    /**
     * Count added/removed lines from a unified diff string.
     * Lines starting with "+" (but not "+++") are additions.
     * Lines starting with "-" (but not "---") are removals.
     */
    private fun countDiffChanges(diff: String) {
        for (line in diff.lines()) {
            when {
                line.startsWith("+++") || line.startsWith("---") -> { /* file header, skip */ }
                line.startsWith("+") -> totalLinesAdded++
                line.startsWith("-") -> totalLinesRemoved++
            }
        }
    }

    /**
     * Extract `task_progress` from tool call arguments and update state.
     *
     * Port of Cline's FocusChainManager.updateFCListFromToolResponse():
     * - The LLM includes a `task_progress` parameter in tool call JSON
     * - We extract it, store in ContextManager, and notify the UI callback
     * - The progress is a markdown checklist string
     *
     * @param params the parsed JSON arguments from the tool call
     */
    private fun extractTaskProgress(params: JsonObject) {
        val progressValue = params[TASK_PROGRESS_PARAM]
        if (progressValue is JsonPrimitive) {
            val markdown = progressValue.contentOrNull
            if (!markdown.isNullOrBlank()) {
                val parsed = contextManager.setTaskProgress(markdown)
                if (parsed != null) {
                    onTaskProgress(parsed)
                }
            }
        }
    }

    /**
     * Classify the risk level of a tool call for the approval gate.
     *
     * - run_command: delegates to [CommandSafetyAnalyzer] for pattern-based classification
     * - edit_file / create_file: "low" (reversible via checkpoints)
     * - revert_file: "medium" (destructive — discards changes)
     *
     * @param toolName the tool about to execute
     * @param argsJson the raw JSON arguments string
     * @return "low", "medium", or "high"
     */
    internal fun assessRisk(toolName: String, argsJson: String): String {
        return when (toolName) {
            "run_command" -> {
                // Extract command from JSON args and classify with CommandSafetyAnalyzer
                try {
                    val args = json.decodeFromString<JsonObject>(argsJson)
                    val command = (args["command"] as? JsonPrimitive)?.contentOrNull ?: ""
                    when (CommandSafetyAnalyzer.classify(command)) {
                        CommandRisk.DANGEROUS -> "high"
                        CommandRisk.RISKY -> "medium"
                        CommandRisk.SAFE -> "low"
                    }
                } catch (_: Exception) {
                    "medium" // can't parse args — default to medium
                }
            }
            "revert_file" -> "medium"
            "edit_file", "create_file" -> "low"
            else -> "medium"
        }
    }
}

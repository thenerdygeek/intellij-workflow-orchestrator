package com.workflow.orchestrator.agent.loop

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.ai.AssistantMessageParser
import com.workflow.orchestrator.core.ai.TextContent
import com.workflow.orchestrator.core.ai.ToolUseContent
import com.workflow.orchestrator.agent.api.dto.ToolCall
import com.workflow.orchestrator.agent.api.dto.ToolDefinition
import com.workflow.orchestrator.agent.hooks.HookEvent
import com.workflow.orchestrator.agent.observability.AgentFileLogger
import com.workflow.orchestrator.agent.observability.SessionMetrics
import com.workflow.orchestrator.agent.hooks.HookManager
import com.workflow.orchestrator.agent.hooks.HookResult
import com.workflow.orchestrator.agent.hooks.HookType
import com.workflow.orchestrator.agent.security.CommandSafetyAnalyzer
import com.workflow.orchestrator.agent.security.CommandRisk
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.truncateOutput
import com.workflow.orchestrator.core.ai.LlmBrain
import com.workflow.orchestrator.core.ai.TokenEstimator
import com.workflow.orchestrator.core.ai.dto.ChatMessage
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentLinkedQueue
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
 * A user message queued while the agent loop is actively running.
 * Drained at the start of each iteration and injected into the conversation context.
 */
data class SteeringMessage(val id: String, val text: String, val timestamp: Long = System.currentTimeMillis())

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
    private var brain: LlmBrain,
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
     * @param planText the plan markdown from the LLM
     * @param needsMoreExploration if true, loop continues immediately (LLM explores more)
     * @param planSteps structured step titles provided by the LLM
     */
    private val onPlanResponse: ((planText: String, needsMoreExploration: Boolean, planSteps: List<String>) -> Unit)? = null,
    /**
     * Channel for receiving user input during the loop.
     * Used in plan mode: after the LLM presents a plan (needsMoreExploration=false),
     * the loop suspends here waiting for the user to type a message, add comments,
     * or click approve. This matches Cline's ask() pattern.
     *
     * When null, the loop does not wait for user input (legacy behavior / sub-agent mode).
     */
    val userInputChannel: Channel<String>? = null,
    /**
     * Callback when the LLM toggles plan mode via enable_plan_mode tool.
     * Sets AgentService.planModeActive and updates the UI so that the next iteration
     * rebuilds tool definitions (removes write tools, adds plan_mode_respond).
     */
    private val onPlanModeToggle: ((Boolean) -> Unit)? = null,
    /**
     * Optional callback for real-time debug log entries.
     * Pushed to the JCEF debug panel when showDebugLog setting is enabled.
     */
    private val onDebugLog: ((level: String, event: String, detail: String, meta: Map<String, Any?>?) -> Unit)? = null,
    /** Optional file logger for structured JSONL agent logs. Always active when provided. */
    private val fileLogger: AgentFileLogger? = null,
    /** Optional per-session metrics accumulator. Records tool durations, API latencies, counts. */
    private val sessionMetrics: SessionMetrics? = null,
    /**
     * Optional provider that returns the environment_details XML block to append to user messages.
     * Called at each real user input injection point (initial task, plan mode feedback,
     * mistake recovery). Returns null to skip injection (e.g. in sub-agents).
     *
     * Port of Cline's getEnvironmentDetails(): lightweight IDE context auto-injected
     * at the entry point of every user message — current mode, open editor, open tabs,
     * context usage, active plan, active ticket.
     */
    val environmentDetailsProvider: (() -> String?)? = null,
    /**
     * Thread-safe queue of user messages sent while the agent is actively running.
     * Drained at the start of each loop iteration (between compaction and LLM call).
     * Ported from Claude Code's mid-turn steering: messages appear as user-role context
     * so the LLM incorporates feedback without restarting the task.
     */
    private val steeringQueue: ConcurrentLinkedQueue<SteeringMessage>? = null,
    /**
     * Callback fired after steering messages are drained and injected into context.
     * The UI uses this to promote queued steering messages to regular chat messages.
     *
     * @param drainedIds the IDs of the steering messages that were injected
     */
    private val onSteeringDrained: ((drainedIds: List<String>) -> Unit)? = null,
    /**
     * Callback fired when the loop retries a failed API call.
     * Used by the UI to show retry status (e.g. "API timeout, retrying 2/3...").
     * Always fires regardless of debug mode — retries are user-visible events.
     */
    private val onRetry: ((attempt: Int, maxAttempts: Int, reason: String, delayMs: Long) -> Unit)? = null,
    /**
     * Optional model fallback manager. When provided with [brainFactory],
     * the loop falls back to cheaper models on network errors and escalates back.
     */
    private val fallbackManager: ModelFallbackManager? = null,
    /**
     * Factory to create a new LlmBrain for a given model ID, with an optional reason
     * string used by the factory to write a recycle marker file into api-debug/.
     * Used by:
     *   - the model fallback manager to switch models mid-loop (reason describes the switch)
     *   - same-tier brain recycling on stream/timeout errors (reason describes the error)
     *   - L2 tier escalation when same-tier recycles are exhausted and fallback is disabled
     */
    private val brainFactory: (suspend (modelId: String, reason: String?) -> LlmBrain)? = null,
    /**
     * Always-built fallback chain (Opus thinking → Opus → Sonnet thinking → Sonnet, no Haiku)
     * used by L2 tier escalation when [fallbackManager] is null but the loop has exhausted
     * same-tier brain recycles. Must contain at least 2 entries for L2 to engage.
     *
     * Built once at task start by AgentService from ModelCache.buildFallbackChain(). Independent
     * from [fallbackManager] which handles the same chain when enableModelFallback is on.
     */
    private val cachedFallbackChain: List<String>? = null,
    /**
     * Callback fired when the loop switches to a different model.
     * Used by the UI to update the model chip and show a status message.
     */
    private val onModelSwitch: ((fromModel: String, toModel: String, reason: String) -> Unit)? = null,
    /**
     * When true, compact context and retry when timeout/network retries are exhausted
     * (instead of failing). Limited to [MAX_COMPACTION_RETRIES] attempts.
     */
    private val compactOnTimeoutExhaustion: Boolean = false,
    /** Tool execution mode: "accumulate" (default) or "stream_interrupt" (Cline-style). */
    private val toolExecutionMode: String = "accumulate"
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
        /** Timeout/network errors get fewer retries — the server is likely down. */
        private const val MAX_TIMEOUT_RETRIES = 3
        /**
         * Max number of same-model brain recycles before escalating to a different
         * model tier (L2 escalation handled in commit 3). A recycle = throw away the
         * OpenAiCompatBrain (and its OkHttpClient + ConnectionPool + activeCall ref)
         * and rebuild it with the same model id, so the next request gets a fresh
         * TCP socket and dispatcher state. Fixes broken keep-alive sockets that
         * OkHttp can't detect (corporate proxy RST injection, etc.).
         *
         * 3 is a "feels right" balance: enough to absorb transient blips, fast
         * enough that real degradations escalate within ~30 seconds.
         */
        private const val MAX_SAME_TIER_RECYCLES = 3
        private const val MAX_CONTEXT_OVERFLOW_RETRIES = 2
        /** Max times to compact context and retry after timeout retries are exhausted. */
        private const val MAX_COMPACTION_RETRIES = 2
        private const val INITIAL_RETRY_DELAY_MS = 1000L
        /** Cap on retry-after header delay to prevent unreasonable waits (Cline: maxDelay 10s). */
        private const val MAX_RETRY_DELAY_MS = 30_000L
        /** Timeout errors — worth fewer retries than rate limits / server errors. */
        private val TIMEOUT_ERRORS = setOf(ErrorType.NETWORK_ERROR, ErrorType.TIMEOUT)
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
        /**
         * Prefix for mid-turn steering messages injected from the user's queued input.
         * Frames the message so the LLM continues its current task rather than treating
         * the steering input as a new task.
         */
        private const val STEERING_MESSAGE_PREFIX =
            "The user sent an additional message while you were working. " +
            "Incorporate their feedback while continuing your current task:\n\n"
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
            return makeCancelled(0)
        }

        LOG.info("[Loop] Starting task (maxIterations=$maxIterations, planMode=$planMode)")

        // Set initial tool definition token count so heuristic estimate includes them.
        // Tool schemas are significant: 30+ tools = 5-10K+ tokens in the API request.
        // Uses static toolDefinitions (not provider) to avoid an extra provider call.
        contextManager.setToolDefinitionTokens(
            TokenEstimator.estimateToolDefinitions(toolDefinitions)
        )

        contextManager.addUserMessage(withEnvDetails(task))

        var iteration = 0
        var consecutiveEmpties = 0
        var consecutiveMistakes = 0  // Cline: consecutiveMistakeCount — tracks text-only (no tool) responses
        val maxConsecutiveMistakes = 3  // Cline: configurable via settings. At max, asks user for feedback.
        var apiRetryCount = 0
        var contextOverflowRetries = 0
        var pendingEscalation = false
        var compactionRetries = 0
        /**
         * Same-model brain recycles in the current tier. Reset to 0 on any successful
         * API call OR when L2 tier escalation switches to a different model.
         * Bounded by [MAX_SAME_TIER_RECYCLES].
         */
        var sameTierRecycles = 0
        /**
         * Index into [cachedFallbackChain] of the live brain's current tier. Used by L2
         * escalation to know how far down the chain we already are. Resynced on every
         * brain swap so it tracks reality, not the chain's "primary" assumption.
         *
         * Initial value is computed from `brain.modelId`'s position in the chain so that
         * a user with a settings override (e.g. forcing Sonnet) starts at the correct
         * tier instead of incorrectly being treated as on Opus thinking.
         *
         * Sentinel: -1 means "current model not in chain" — L2 is disabled in that case
         * because escalating into the chain would land on a tier the user explicitly
         * didn't pick. The L2 guard checks `l2TierIdx >= 0` before any index arithmetic.
         */
        var l2TierIdx = cachedFallbackChain?.indexOf(brain.modelId) ?: -1

        while (!cancelled.get() && iteration < maxIterations) {
            iteration++
            val iterationStartTime = System.currentTimeMillis()
            LOG.info("[Loop] Iteration $iteration -- ${contextManager.messageCount()} messages, ${"%.1f".format(contextManager.utilizationPercent())}% context")

            // Stage 0: Compact if needed
            if (contextManager.shouldCompact()) {
                val utilBefore = contextManager.utilizationPercent()
                val tokensBefore = contextManager.tokenEstimate()
                LOG.info("[Loop] Context compaction triggered at ${"%.1f".format(utilBefore)}%")
                contextManager.compact(brain)
                val tokensAfter = contextManager.tokenEstimate()
                fileLogger?.logCompaction(sessionId ?: "", "utilization_${"%d".format(utilBefore.toInt())}pct", tokensBefore, tokensAfter)
                sessionMetrics?.recordCompaction(tokensBefore, tokensAfter)
                onDebugLog?.invoke("warn", "compaction", "Compacted: ${"%.1f".format(utilBefore)}% — $tokensBefore → $tokensAfter tokens",
                    mapOf("tokensBefore" to tokensBefore, "tokensAfter" to tokensAfter))
            }

            // Stage 0.5: Drain steering messages (ported from Claude Code's mid-turn steering)
            // User messages sent while the loop was running are queued in steeringQueue.
            // We drain them here — after compaction (so context is fresh) but before the
            // LLM call (so the model sees the user's feedback in context).
            if (steeringQueue != null && steeringQueue.isNotEmpty()) {
                val drained = generateSequence { steeringQueue.poll() }.toList()
                if (drained.isNotEmpty()) {
                    val combinedText = drained.joinToString("\n\n") { it.text }
                    contextManager.addUserMessage(withEnvDetails(STEERING_MESSAGE_PREFIX + combinedText))
                    LOG.info("[Loop] Injected ${drained.size} steering message(s) into context")
                    onSteeringDrained?.invoke(drained.map { it.id })
                }
            }

            // Stage 1: Call LLM (use dynamic definitions if tool_search has loaded new tools)
            val currentToolDefs = toolDefinitionProvider?.invoke() ?: toolDefinitions
            // Update tool token count if deferred tools were loaded since last iteration
            contextManager.setToolDefinitionTokens(
                TokenEstimator.estimateToolDefinitions(currentToolDefs)
            )

            // Block-based streaming presentation (Cline port)
            // Accumulates text, re-parses on every chunk, sends only TextContent to UI.
            // Tool/param names fetched from brain (updated by AgentService when deferred tools load).
            val accumulatedText = StringBuilder()
            var lastPresentedTextLength = 0
            val currentToolNames = brain.toolNameSet
            val currentParamNames = brain.paramNameSet

            val apiResult = brain.chatStream(
                messages = contextManager.getMessages(),
                tools = currentToolDefs,
                maxTokens = maxOutputTokens,
                onChunk = { chunk ->
                    val text = chunk.choices.firstOrNull()?.delta?.content ?: return@chatStream

                    // Always accumulate
                    accumulatedText.append(text)

                    // Re-parse full accumulated text
                    val blocks = AssistantMessageParser.parse(
                        accumulatedText.toString(),
                        currentToolNames,
                        currentParamNames
                    )

                    // Extract visible text (TextContent blocks only)
                    val visibleText = blocks.filterIsInstance<TextContent>()
                        .joinToString("") { it.content }

                    // Only send NEW text to UI (delta since last presentation)
                    val stripped = AssistantMessageParser.stripPartialTag(visibleText)
                    if (stripped.length > lastPresentedTextLength) {
                        val delta = stripped.substring(lastPresentedTextLength)
                        onStreamChunk(delta)
                        lastPresentedTextLength = stripped.length
                    }

                    // Stream-interrupt: if a tool block just completed, interrupt stream
                    if (toolExecutionMode == "stream_interrupt") {
                        val completedTool = blocks.filterIsInstance<ToolUseContent>()
                            .firstOrNull { !it.partial }
                        if (completedTool != null) {
                            brain.interruptStream()
                        }
                    }
                }
            )

            // Stage 2: Handle API errors with retry for transient failures
            if (apiResult is ApiResult.Error) {
                // Context overflow detection (from Cline)
                // Cline detects context window exceeded errors and auto-truncates + retries
                if (isContextOverflowError(apiResult) && contextOverflowRetries < MAX_CONTEXT_OVERFLOW_RETRIES) {
                    contextOverflowRetries++
                    LOG.warn("[Loop] Context overflow detected, compacting and retrying ($contextOverflowRetries/$MAX_CONTEXT_OVERFLOW_RETRIES)")
                    fileLogger?.logRetry(sessionId ?: "", "context_overflow", iteration)
                    onDebugLog?.invoke("warn", "retry", "Context overflow, compacting ($contextOverflowRetries/$MAX_CONTEXT_OVERFLOW_RETRIES)", null)
                    // Force aggressive compaction
                    contextManager.compact(brain)
                    iteration-- // Don't count overflow retries as iterations
                    continue
                }

                val isTimeoutError = apiResult.type in TIMEOUT_ERRORS
                val maxRetries = if (isTimeoutError) MAX_TIMEOUT_RETRIES else MAX_API_RETRIES
                if (apiResult.type in RETRYABLE_ERRORS && apiRetryCount < maxRetries) {
                    apiRetryCount++

                    // Tracks whether any of the three recovery layers (L1-fallback,
                    // L1-recycle, L2) has already swapped the brain in this iteration.
                    // Used so the L1-recycle branch can fire even when L1-fallback is
                    // enabled but its chain is exhausted (nextModel == null) — otherwise
                    // the loop would retry against the same dead socket.
                    var brainSwapAttempted = false

                    // L1-fallback: Smart model fallback on timeout/network errors (when fallback is enabled)
                    if (fallbackManager != null && brainFactory != null && apiResult.type in TIMEOUT_ERRORS) {
                        val oldModel = brain.modelId
                        if (pendingEscalation) {
                            // Escalation back to primary failed — always swaps
                            val revertModel = fallbackManager.onEscalationFailed()
                            brain = brainFactory.invoke(revertModel, "Fallback escalation failed — reverting from $oldModel to $revertModel")
                            onModelSwitch?.invoke(oldModel, revertModel, "Escalation failed — reverting")
                            LOG.info("[Loop] Escalation failed, reverting: $oldModel → $revertModel")
                            pendingEscalation = false
                            // Tier swap: resync L2 index + refill recycle budget for the new tier
                            l2TierIdx = cachedFallbackChain?.indexOf(revertModel) ?: -1
                            sameTierRecycles = 0
                            brainSwapAttempted = true
                        } else {
                            // Normal fallback — advance down the chain if possible
                            val nextModel = fallbackManager.onNetworkError()
                            if (nextModel != null) {
                                brain = brainFactory.invoke(nextModel, "Network error on $oldModel: ${apiResult.type.name} — falling back to $nextModel")
                                onModelSwitch?.invoke(oldModel, nextModel, "Network error — falling back")
                                LOG.info("[Loop] Model fallback: $oldModel → $nextModel")
                                // Tier swap: resync L2 index + refill recycle budget for the new tier
                                l2TierIdx = cachedFallbackChain?.indexOf(nextModel) ?: -1
                                sameTierRecycles = 0
                                brainSwapAttempted = true
                            }
                            // else: fallback chain exhausted — fall through to L1-recycle below
                            // so we at least retry against a fresh OkHttpClient instead of the
                            // same dead socket.
                        }
                    }

                    // L1-recycle: Same-model brain recycle when L1-fallback did not swap.
                    // Throws away the OpenAiCompatBrain (and its OkHttpClient + ConnectionPool +
                    // activeCall ref) and rebuilds it with the SAME model id. Fixes broken
                    // sockets / dead TCP state that OkHttp can't detect (e.g. corporate proxy
                    // RST injection mid-stream). Conversation context is unaffected — we only
                    // recycle the network plumbing, not the message history.
                    //
                    // Fires when either:
                    //   - fallback is disabled (original motivation), OR
                    //   - fallback is enabled but its chain is exhausted (M3 fix: was leaving
                    //     the loop to retry against the same dead socket)
                    // Bounded by MAX_SAME_TIER_RECYCLES — beyond that, the issue likely is
                    // not socket-level and L2 tier escalation takes over.
                    if (!brainSwapAttempted && brainFactory != null && apiResult.type in TIMEOUT_ERRORS && sameTierRecycles < MAX_SAME_TIER_RECYCLES) {
                        sameTierRecycles++
                        val sameModel = brain.modelId
                        val reason = "Same-tier recycle #$sameTierRecycles on ${apiResult.type.name}: ${apiResult.message.take(120)}"
                        LOG.warn("[Loop] Recycling brain ($sameTierRecycles/$MAX_SAME_TIER_RECYCLES) on ${apiResult.type} — model unchanged: ${sameModel.substringAfterLast("::")}")
                        onDebugLog?.invoke("warn", "recycle", "Brain recycled (#$sameTierRecycles) — fresh OkHttp pool", mapOf(
                            "errorType" to apiResult.type.name,
                            "model" to sameModel,
                            "recycleCount" to sameTierRecycles
                        ))
                        brain = brainFactory.invoke(sameModel, reason)
                        // Surface the recycle in the UI even though the model id is unchanged.
                        // The chip text stays the same; the controller may flash the fallback
                        // indicator (amber border + Zap icon) with the recycle reason as tooltip,
                        // matching the existing onModelSwitch convention.
                        onModelSwitch?.invoke(sameModel, sameModel, "Brain recycled #$sameTierRecycles — fresh OkHttp pool (${apiResult.type.name})")
                        brainSwapAttempted = true
                    }

                    // L2: Tier escalation when same-tier recycles are exhausted and an
                    // alternate tier is available. Only engages when fallbackManager is
                    // null (when fallback is enabled, fallbackManager owns chain advancement
                    // and L2 would race against it on the same chain). Crosses the gateway
                    // routing layer for -latest aliased models — the only client-side
                    // intervention that actually changes which backend serves the request.
                    //
                    // Guard order matters: check l2TierIdx >= 0 BEFORE the size arithmetic
                    // so the sentinel "model not in chain" (-1) cleanly disables L2.
                    if (
                        !brainSwapAttempted &&
                        fallbackManager == null &&
                        brainFactory != null &&
                        apiResult.type in TIMEOUT_ERRORS &&
                        sameTierRecycles >= MAX_SAME_TIER_RECYCLES &&
                        cachedFallbackChain != null &&
                        l2TierIdx >= 0 &&
                        l2TierIdx + 1 < cachedFallbackChain.size
                    ) {
                        l2TierIdx++
                        val oldModel = brain.modelId
                        val newTierModel = cachedFallbackChain[l2TierIdx]
                        val reason = "L2 tier escalation: $MAX_SAME_TIER_RECYCLES same-tier recycles exhausted on ${apiResult.type.name}; advancing $oldModel → $newTierModel"
                        LOG.warn("[Loop] L2 tier escalation: ${oldModel.substringAfterLast("::")} → ${newTierModel.substringAfterLast("::")}")
                        onDebugLog?.invoke("warn", "tier_escalation", "Tier escalated after $MAX_SAME_TIER_RECYCLES recycles", mapOf(
                            "fromModel" to oldModel,
                            "toModel" to newTierModel,
                            "tierIdx" to l2TierIdx
                        ))
                        brain = brainFactory.invoke(newTierModel, reason)
                        onModelSwitch?.invoke(oldModel, newTierModel, "Same-tier recovery exhausted — escalating tier")
                        sameTierRecycles = 0  // reset budget for the new tier
                        brainSwapAttempted = true
                    }
                    // Use server-provided retry delay if available (ported from Cline's retry.ts),
                    // otherwise fall back to exponential backoff
                    val delayMs = apiResult.retryAfterMs
                        ?.coerceAtMost(MAX_RETRY_DELAY_MS)
                        ?: (INITIAL_RETRY_DELAY_MS * (1L shl (apiRetryCount - 1)))
                    val reason = when (apiResult.type) {
                        ErrorType.NETWORK_ERROR -> "Network error"
                        ErrorType.TIMEOUT -> "Request timeout"
                        ErrorType.RATE_LIMITED -> "Rate limited"
                        ErrorType.SERVER_ERROR -> "Server error"
                        else -> apiResult.type.name
                    }
                    LOG.warn("[Loop] API retry $apiRetryCount/$maxRetries ($reason, delay=${delayMs}ms)")
                    fileLogger?.logRetry(sessionId ?: "", "api_${apiResult.type.name.lowercase()}", iteration)
                    onDebugLog?.invoke("warn", "retry", "API retry $apiRetryCount/$maxRetries: $reason", mapOf("errorType" to apiResult.type.name))
                    onRetry?.invoke(apiRetryCount, maxRetries, reason, delayMs)
                    delay(delayMs)
                    iteration-- // Don't count retries as iterations
                    continue
                }
                // Context compaction strategy: compact and retry instead of failing
                if (compactOnTimeoutExhaustion && apiResult.type in TIMEOUT_ERRORS && compactionRetries < MAX_COMPACTION_RETRIES) {
                    compactionRetries++
                    LOG.warn("[Loop] Timeout retries exhausted, compacting context and retrying ($compactionRetries/$MAX_COMPACTION_RETRIES)")
                    onRetry?.invoke(compactionRetries, MAX_COMPACTION_RETRIES, "Compacting context and retrying", 0)
                    contextManager.compact(brain)
                    apiRetryCount = 0 // Reset retry count for the fresh attempt
                    iteration-- // Don't count as iteration
                    continue
                }
                LOG.warn("[Loop] Task failed after $iteration iterations: ${apiResult.message.take(200)}")
                return makeFailed(apiResult.message, iteration)
            }

            val response = (apiResult as ApiResult.Success).data

            // Reset retry counts on successful API call
            apiRetryCount = 0
            contextOverflowRetries = 0
            compactionRetries = 0
            sameTierRecycles = 0  // recycle budget refills on every successful call
            pendingEscalation = false // if we were escalating, it succeeded

            // Smart model escalation: try primary model after cooldown
            if (fallbackManager != null && brainFactory != null && !fallbackManager.isPrimary()) {
                val escalationModel = fallbackManager.onIterationSuccess()
                if (escalationModel != null) {
                    val oldModel = brain.modelId
                    brain = brainFactory.invoke(escalationModel, "Fallback cooldown elapsed — escalating from $oldModel to $escalationModel")
                    onModelSwitch?.invoke(oldModel, escalationModel, "Escalating back")
                    LOG.info("[Loop] Model escalation: $oldModel → $escalationModel")
                    pendingEscalation = true
                    // Tier swap: resync L2 index for the new tier
                    l2TierIdx = cachedFallbackChain?.indexOf(escalationModel) ?: -1
                }
            }

            // Stage 3: Update token tracking (ported from Cline's cost tracking)
            // Cline accumulates tokensIn/tokensOut in HistoryItem after each API call.
            response.usage?.let { usage ->
                totalTokensUsed += usage.totalTokens
                totalInputTokens += usage.promptTokens
                totalOutputTokens += usage.completionTokens
                contextManager.updateTokens(usage.promptTokens)
                // Pass CURRENT context usage (promptTokens = how full the context window is now)
                // not cumulative totals — the UI shows "X / maxInputTokens" as a progress bar.
                onTokenUpdate?.invoke(usage.promptTokens, usage.completionTokens)
                val apiLatencyMs = System.currentTimeMillis() - iterationStartTime
                fileLogger?.logApiCall(sessionId ?: "", apiLatencyMs, usage.promptTokens, usage.completionTokens, null)
                sessionMetrics?.recordApiCall(apiLatencyMs, usage.promptTokens, usage.completionTokens)
                onDebugLog?.invoke("info", "api_call", "API: ${usage.promptTokens}p + ${usage.completionTokens}c tokens, ${apiLatencyMs}ms",
                    mapOf("latencyMs" to apiLatencyMs, "promptTokens" to usage.promptTokens, "completionTokens" to usage.completionTokens))
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
                        contextManager.addUserMessage(withEnvDetails(userInputChannel.receive()))
                        consecutiveMistakes = 0
                    } else if (consecutiveMistakes >= maxConsecutiveMistakes && userInputChannel != null) {
                        // Cline pattern: at max mistakes, ask user for feedback instead of failing
                        LOG.warn("[Loop] Max consecutive mistakes ($maxConsecutiveMistakes) — waiting for user feedback")
                        contextManager.addUserMessage(withEnvDetails(userInputChannel.receive()))
                        consecutiveMistakes = 0
                    } else if (consecutiveMistakes >= maxConsecutiveMistakes) {
                        // No user input channel (sub-agent) — fail
                        return makeFailed("Agent failed to use tools after $maxConsecutiveMistakes attempts.", iteration)
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
                        return makeFailed(
                            "Provider returned $MAX_CONSECUTIVE_EMPTIES consecutive empty responses. Check model/provider configuration.",
                            iteration
                        )
                    }
                    contextManager.addUserMessage(EMPTY_RESPONSE_ERROR)
                }
            }
        }

        if (cancelled.get()) {
            LOG.info("[Loop] Task cancelled at iteration $iteration")
            return makeCancelled(iteration)
        }

        LOG.warn("[Loop] Task failed after $iteration iterations: exceeded maximum iterations ($maxIterations)")
        return makeFailed(
            "Exceeded maximum iterations ($maxIterations). The task may be too complex or the model is stuck.",
            iteration
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
                return makeCancelled(iteration)
            }

            val toolName = call.function.name
            val toolCallId = call.id
            val startTime = System.currentTimeMillis()

            // Loop detection (from Cline) — check BEFORE executing
            when (loopDetector.recordToolCall(toolName, call.function.arguments)) {
                LoopStatus.HARD_LIMIT -> {
                    LOG.warn("[Loop] Hard loop limit reached: '$toolName' called ${loopDetector.currentCount} times consecutively")
                    fileLogger?.logLoopDetection(sessionId ?: "", toolName, loopDetector.currentCount, isHard = true)
                    onDebugLog?.invoke("error", "loop", "Loop HARD limit: $toolName — aborting", null)
                    reportToolError(call, startTime, LOOP_HARD_FAILURE)
                    return makeFailed(
                        "Loop detected: '$toolName' called ${loopDetector.currentCount} times with identical arguments.",
                        iteration
                    )
                }
                LoopStatus.SOFT_WARNING -> {
                    LOG.warn("[Loop] Soft loop warning: '$toolName' called ${loopDetector.currentCount} times consecutively")
                    fileLogger?.logLoopDetection(sessionId ?: "", toolName, loopDetector.currentCount, isHard = false)
                    onDebugLog?.invoke("warn", "loop", "Loop warning: $toolName called ${loopDetector.currentCount}x", null)
                    // Inject warning but continue execution (give the model a chance to self-correct)
                    contextManager.addUserMessage(LOOP_SOFT_WARNING)
                }
                LoopStatus.OK -> { /* no action */ }
            }

            val tool = toolResolver?.invoke(toolName) ?: tools[toolName]
            if (tool == null) {
                // Unknown tool
                val allToolNames = if (toolResolver != null) "use tool_search to find tools" else tools.keys.joinToString(", ")
                reportToolError(call, startTime, "Unknown tool: '$toolName'. Available tools: $allToolNames")
                continue
            }

            // Plan mode guard: block write tools even if the LLM hallucinates them
            if (planMode && toolName in WRITE_TOOLS) {
                reportToolError(
                    call, startTime,
                    "Error: '$toolName' is blocked in plan mode. You can only read, search, and analyze code."
                )
                continue
            }

            // act_mode_respond consecutive-call guard (ported from Cline).
            // Cline: "This tool cannot be called consecutively — each use must be
            // followed by a different tool call or completion."
            if (toolName == "act_mode_respond" && lastToolName == "act_mode_respond") {
                reportToolError(
                    call, startTime,
                    "Error: act_mode_respond cannot be called consecutively. " +
                        "Use a different tool or call attempt_completion."
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
                        reportToolError(call, startTime, "Tool execution denied by user.")
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
                reportToolError(call, startTime, "Invalid JSON arguments for '$toolName': ${e.message}")
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
                    reportToolError(call, startTime, "Tool '$toolName' blocked by PreToolUse hook: ${preHookResult.reason}")
                    continue
                }
            }

            // Fire start callback (empty result, zero duration = RUNNING)
            LOG.info("[Loop] Executing tool: $toolName (${call.function.arguments.take(200)})")
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
                val exceptionDurationMs = System.currentTimeMillis() - startTime
                fileLogger?.logToolCall(
                    sessionId = sessionId ?: "",
                    toolName = toolName,
                    durationMs = exceptionDurationMs,
                    isError = true,
                    errorMessage = errorMsg.take(500)
                )
                sessionMetrics?.recordToolCall(toolName, exceptionDurationMs, true)
                onDebugLog?.invoke("error", "tool_call", "$toolName EXCEPTION (${exceptionDurationMs}ms)",
                    mapOf("tool" to toolName, "error" to errorMsg.take(200)))
                reportToolError(call, startTime, errorMsg)
                continue
            }

            val durationMs = System.currentTimeMillis() - startTime
            val truncatedContent = truncateOutput(toolResult.content)

            if (toolResult.isError) {
                LOG.warn("[Loop] Tool $toolName failed: ${toolResult.content.take(200)}")
            } else {
                LOG.info("[Loop] Tool $toolName completed in ${durationMs}ms (OK)")
            }
            fileLogger?.logToolCall(
                sessionId = sessionId ?: "",
                toolName = toolName,
                durationMs = durationMs,
                isError = toolResult.isError,
                args = call.function.arguments.take(500),
                errorMessage = if (toolResult.isError) toolResult.content.take(500) else null,
                tokenEstimate = toolResult.tokenEstimate
            )
            sessionMetrics?.recordToolCall(toolName, durationMs, toolResult.isError)
            onDebugLog?.invoke(
                if (toolResult.isError) "error" else "info",
                "tool_call",
                "$toolName ${if (toolResult.isError) "ERROR" else "OK"} (${durationMs}ms)",
                mapOf("tool" to toolName, "duration" to durationMs, "tokens" to toolResult.tokenEstimate)
            )

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

            // Notify callback (includes editDiff for file change tools ��� ported from Cline)
            onToolCall(
                ToolCallProgress(
                    toolName = toolName,
                    args = call.function.arguments,
                    result = toolResult.summary,
                    output = toolResult.content.takeIf { it != toolResult.summary },
                    durationMs = durationMs,
                    isError = toolResult.isError,
                    toolCallId = toolCallId,
                    editDiff = toolResult.diff
                )
            )

            // Artifact rendering is now driven inside RenderArtifactTool via
            // ArtifactResultRegistry — the tool awaits the sandbox round-trip before
            // returning, so the LLM sees the real render outcome (incl. missing
            // symbols / runtime errors) instead of the previous fire-and-forget path.

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
                LOG.info("[Loop] Plan presented (needsMoreExploration=${toolResult.needsMoreExploration}, steps=${toolResult.planSteps.size})")
                onPlanResponse?.invoke(toolResult.content, toolResult.needsMoreExploration, toolResult.planSteps)

                if (!toolResult.needsMoreExploration && userInputChannel != null) {
                    // Wait for user input (matches Cline's ask() pattern).
                    // The user can: type in chat, add step comments, or click approve.
                    // Each sends a message into the channel, which resumes the loop.
                    contextManager.addUserMessage(withEnvDetails(userInputChannel.receive()))
                    // Continue the loop — LLM will see the user's message and respond
                }
                // needs_more_exploration=true OR no channel: loop continues immediately
            }

            // Handle enable_plan_mode: activate plan mode so next iteration
            // rebuilds tool definitions (removes write tools, adds plan_mode_respond)
            if (toolResult.enablePlanMode) {
                LOG.info("[Loop] Plan mode enabled by LLM via enable_plan_mode tool")
                onPlanModeToggle?.invoke(true)
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

    /** Build a Failed result with current loop tracking state. */
    private fun makeFailed(error: String, iterations: Int): LoopResult.Failed = LoopResult.Failed(
        error = error,
        iterations = iterations,
        tokensUsed = totalTokensUsed,
        inputTokens = totalInputTokens,
        outputTokens = totalOutputTokens,
        filesModified = filesModifiedList(),
        linesAdded = totalLinesAdded,
        linesRemoved = totalLinesRemoved
    )

    /** Build a Cancelled result with current loop tracking state. */
    private fun makeCancelled(iterations: Int): LoopResult.Cancelled = LoopResult.Cancelled(
        iterations = iterations,
        tokensUsed = totalTokensUsed,
        inputTokens = totalInputTokens,
        outputTokens = totalOutputTokens,
        filesModified = filesModifiedList(),
        linesAdded = totalLinesAdded,
        linesRemoved = totalLinesRemoved
    )

    /**
     * Append the latest environment_details block to a user message.
     * Returns the message unchanged if the provider returns null.
     */
    private fun withEnvDetails(message: String): String {
        val envDetails = environmentDetailsProvider?.invoke()
        return if (envDetails != null) "$message\n\n$envDetails" else message
    }

    /**
     * Record a tool call error: add to context as a tool result and notify the UI callback.
     * Used by all the pre-execution guard paths (unknown tool, plan mode block, denied, parse error, etc.).
     */
    private fun reportToolError(
        call: ToolCall,
        startTime: Long,
        errorMsg: String
    ) {
        val toolName = call.function.name
        val toolCallId = call.id
        contextManager.addToolResult(
            toolCallId = toolCallId,
            content = errorMsg,
            isError = true,
            toolName = toolName
        )
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
    }

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
        val markdown = (params[TASK_PROGRESS_PARAM] as? JsonPrimitive)?.contentOrNull
        if (!markdown.isNullOrBlank()) {
            contextManager.setTaskProgress(markdown)?.let { onTaskProgress(it) }
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
    internal fun assessRisk(toolName: String, argsJson: String): String = when (toolName) {
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

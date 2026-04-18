package com.workflow.orchestrator.agent.loop

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.ai.AssistantMessageContent
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
import com.workflow.orchestrator.agent.session.*
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolOutputConfig
import com.workflow.orchestrator.agent.tools.ToolOutputSpiller
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.ToolResultType
import com.workflow.orchestrator.agent.tools.estimateTokens
import com.workflow.orchestrator.agent.tools.truncateOutput
import com.workflow.orchestrator.core.ai.LlmBrain
import com.workflow.orchestrator.core.ai.TokenEstimator
import com.workflow.orchestrator.core.ai.dto.ChatMessage
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
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
 * Delegates all [AgentTool] behaviour to [delegate] but overrides
 * [AgentTool.requestApproval] to route through the loop's [gate].
 * Allows write actions inside tools to call [requestApproval] without
 * being added to [ApprovalPolicy.APPROVAL_TOOLS].
 */
private class ApprovalGatedTool(
    private val delegate: com.workflow.orchestrator.agent.tools.AgentTool,
    private val gate: (suspend (String, String, String, Boolean) -> ApprovalResult)?
) : com.workflow.orchestrator.agent.tools.AgentTool by delegate {
    override suspend fun requestApproval(
        toolName: String, args: String, riskLevel: String, allowSessionApproval: Boolean
    ): ApprovalResult = gate?.invoke(toolName, args, riskLevel, allowSessionApproval)
        ?: ApprovalResult.APPROVED
}

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
    private val maxIterations: Int = 200,
    private val planMode: Boolean = false,
    private val onCheckpoint: (suspend () -> Unit)? = null,
    /**
     * Optional Cline-style session persistence handler.
     * When provided, every streaming chunk, assistant message, and tool result is
     * persisted to ui_messages.json + api_conversation_history.json via this handler.
     * Nullable: sub-agents and tests pass null (no persistence overhead).
     *
     * All calls are awaited inline (NOT in launch{}) because AgentLoop.run() already
     * executes on Dispatchers.IO — fire-and-forget would defeat the per-change guarantee.
     */
    private val messageStateHandler: MessageStateHandler? = null,
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
     * @param allowSessionApproval whether the UI should offer "allow for session" (false for run_command)
     * @return the user's decision
     */
    private val approvalGate: (suspend (toolName: String, args: String, riskLevel: String, allowSessionApproval: Boolean) -> ApprovalResult)? = null,
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
    val userInputChannel: Channel<String>? = null,
    /**
     * Callback when the LLM toggles plan mode via enable_plan_mode tool.
     * Sets AgentService.planModeActive and updates the UI so that the next iteration
     * rebuilds tool definitions (removes write tools, adds plan_mode_respond).
     */
    private val onPlanModeToggle: ((Boolean) -> Unit)? = null,
    /**
     * Callback fired when the LLM discards the current plan via discard_plan tool.
     * The UI uses this to clear the active plan card without presenting a replacement.
     * Only callable in plan mode; the loop continues after dismissal.
     */
    private val onPlanDiscarded: (suspend () -> Unit)? = null,
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
    /**
     * Session-scoped approval store. Tracks which tools the user has approved
     * for the current session. Injected from the controller/session level so
     * approvals persist across follow-up messages (multiple loop runs).
     * Defaults to a fresh store for backward compatibility (tests, sub-agents).
     */
    private val sessionApprovalStore: SessionApprovalStore = SessionApprovalStore(),
    /** Tool execution mode: "accumulate" (default) or "stream_interrupt" (Cline-style). */
    private val toolExecutionMode: String = "accumulate",
    /** Dynamic provider for known tool names (re-read from registry each iteration for deferred tools). */
    private val toolNameProvider: (() -> Set<String>)? = null,
    /** Dynamic provider for known param names (re-read from registry each iteration for deferred tools). */
    private val paramNameProvider: (() -> Set<String>)? = null,
    /**
     * Optional callback that returns a fresh composed system prompt each iteration.
     * Used by sub-agents with deferred tools: when tool_search activates a new tool,
     * the system prompt must include its schema for subsequent API calls.
     *
     * Called at the start of every iteration when non-null. The contextManager's
     * system prompt is updated unconditionally — the provider is responsible for
     * returning a stable string when nothing has changed (cheap, no API calls).
     *
     * Null for the main agent (which manages its own system prompt via AgentService).
     */
    private val systemPromptProvider: (() -> String)? = null,
    /**
     * Optional output spiller for persisting large tool outputs to disk.
     * When set, outputs exceeding SPILL_THRESHOLD_CHARS or explicitly requested via
     * output_file=true are written to disk and a preview is returned to the LLM.
     */
    private val outputSpiller: ToolOutputSpiller? = null,
    /**
     * Callback fired when the loop is about to suspend on [userInputChannel] waiting
     * for user input (plan-mode text turns, consecutive-mistakes recovery). Without
     * this signal the UI keeps showing the "working" spinner even though nothing is
     * happening server-side. The controller should clear busy, enable steering mode,
     * unlock input, and surface [reason] so the user knows the loop is idle and why.
     *
     * Fires from the AgentLoop coroutine; invoke UI work via invokeLater on the EDT.
     */
    private val onAwaitingUserInput: ((reason: String) -> Unit)? = null,
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

    // Session-scoped approval is handled by the injected sessionApprovalStore
    // (lives at the session level, persists across loop runs within the same session).

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
            "Otherwise, if you have not completed the task and do not need additional information, " +
            "then proceed with the next step of the task.\n" +
            "(This is an automated message, so do not respond to it conversationally.)"

        /**
         * Message for empty responses (no text, no tools).
         * Ported from Cline: empty responses are treated as PROVIDER ERRORS,
         * separate from text-only (model mistakes).
         *
         * NOTE: Must include the tool-use directive. Without it, the model reads
         * "Please retry" and responds with a text-only explanation of the error,
         * which drops into Case B (TEXT_ONLY_NUDGE). Case B does NOT reset
         * consecutiveEmpties (except in plan-mode conversational branches where a
         * text-only response is a genuine exchange, not a stall) — without this
         * guard the alternating text-only ↔ empty cycle would continue indefinitely.
         */
        private const val EMPTY_RESPONSE_ERROR =
            "Invalid API Response: The provider returned an empty or unparsable response. " +
            "This is a provider-side issue where the model failed to generate valid output. " +
            "Please retry using a tool call.\n\n" +
            "If you have completed the task, use the attempt_completion tool. " +
            "If you need more information, use the ask_followup_question tool. " +
            "Otherwise proceed with the next step using an appropriate tool. " +
            "(This is an automated message — do not respond to it conversationally.)"
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

        /**
         * Tools that bypass PreToolUse/PostToolUse hooks.
         * Ported from Claude Code's task-system behavior — task management tools are
         * internal bookkeeping and should not be observable by external hooks.
         */
        private val HOOK_EXEMPT: Set<String> = setOf(
            "task_create", "task_update", "task_list", "task_get"
        )

        /** Tools that mutate state — blocked when plan mode is active. */
        val WRITE_TOOLS = setOf(
            "edit_file", "create_file", "run_command", "revert_file",
            "kill_process", "send_stdin", "format_code", "optimize_imports",
            "refactor_rename"
        )

        /** Subset of write tools that require user approval via the approval gate. */
        val APPROVAL_TOOLS = ApprovalPolicy.APPROVAL_TOOLS

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
            onDebugLog?.invoke("info", "loop_exit", "Exit: cancelled_before_start", null)
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
        /** Tracks the last accumulated assistant text across iterations for abort persistence. */
        var lastAccumulatedText = ""
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

            // Stage 0.75: Refresh system prompt if deferred tools were activated
            // (sub-agent path only — systemPromptProvider is null for the main agent)
            systemPromptProvider?.invoke()?.let { freshPrompt ->
                contextManager.setSystemPrompt(freshPrompt)
            }

            // Stage 1: Call LLM (use dynamic definitions if tool_search has loaded new tools)
            val currentToolDefs = toolDefinitionProvider?.invoke() ?: toolDefinitions
            // Update tool token count if deferred tools were loaded since last iteration
            contextManager.setToolDefinitionTokens(
                TokenEstimator.estimateToolDefinitions(currentToolDefs)
            )

            // Block-based streaming presentation (Cline port)
            // Accumulates text, re-parses on every chunk, sends only TextContent to UI.
            // Tool/param names read from providers (updated when deferred tools load via request_tools).
            val accumulatedText = StringBuilder()
            var lastPresentedTextLength = 0
            var cachedBlocks: List<AssistantMessageContent>? = null
            var cachedStrippedText = ""
            val currentToolNames = toolNameProvider?.invoke() ?: brain.toolNameSet
            val currentParamNames = paramNameProvider?.invoke() ?: brain.paramNameSet

            // Persist api_req_started UI message before the LLM call (Cline pattern, I7 fix).
            // AgentLoop runs on Dispatchers.IO — all messageStateHandler calls are awaited inline.
            messageStateHandler?.addToClineMessages(UiMessage(
                ts = System.currentTimeMillis(),
                type = UiMessageType.SAY,
                say = UiSay.API_REQ_STARTED,
                text = "{}"  // Updated with cost info after response
            ))
            val apiReqStartedIdx = messageStateHandler?.getClineMessages()?.lastIndex ?: -1
            var isFirstStreamChunk = true

            val apiResult = brain.chatStream(
                messages = contextManager.getMessages(),
                tools = currentToolDefs,
                maxTokens = maxOutputTokens,
                onChunk = { chunk ->
                    val text = chunk.choices.firstOrNull()?.delta?.content ?: return@chatStream

                    // Always accumulate
                    accumulatedText.append(text)

                    // Conditional re-parse: skip when no XML structural character in chunk
                    val needsParse = cachedBlocks == null || text.contains('<') || text.contains('>')
                    val blocks = if (needsParse) {
                        AssistantMessageParser.parse(
                            accumulatedText.toString(),
                            currentToolNames,
                            currentParamNames
                        ).also { cachedBlocks = it }
                    } else {
                        cachedBlocks!!
                    }

                    // Extract visible text (TextContent blocks only)
                    val visibleText = blocks.filterIsInstance<TextContent>()
                        .joinToString("\n\n") { it.content }

                    // Only send NEW text to UI (delta since last presentation)
                    val stripped = if (needsParse) {
                        // When no tool calls present, use accumulated text directly to preserve whitespace
                        // (TextContent.content is trimmed by the parser, which loses trailing spaces at chunk boundaries)
                        val hasToolCalls = blocks.any { it is ToolUseContent }
                        val base = if (hasToolCalls) visibleText else accumulatedText.toString()
                        AssistantMessageParser.stripPartialTag(base)
                            .also { cachedStrippedText = it }
                    } else {
                        // Skip-parse path: only append plain text when no tool call is in flight.
                        //
                        // BUG FIXED: if a partial tool call is in cachedBlocks, `text` is raw
                        // parameter content (e.g. file path bytes inside <path>...</path>). Appending
                        // it to cachedStrippedText inflates lastPresentedTextLength. When the tool
                        // close tag arrives and triggers a real parse, stripped = visibleText (just
                        // pre-tool text) < lastPresentedTextLength → the condition below is forever
                        // false → all text after the tool call becomes invisible ("stops abruptly").
                        val hasPendingTool = cachedBlocks?.any { it is ToolUseContent && it.partial } == true
                        if (hasPendingTool) {
                            cachedStrippedText  // tool param in flight — don't leak it to the display
                        } else {
                            (cachedStrippedText + text).also { cachedStrippedText = it }
                        }
                    }
                    if (stripped.length > lastPresentedTextLength) {
                        val delta = stripped.substring(lastPresentedTextLength)
                        onStreamChunk(delta)
                        lastPresentedTextLength = stripped.length
                    } else if (needsParse && stripped.length < lastPresentedTextLength) {
                        // Safety reset: a fresh parse gave shorter text than the watermark, meaning
                        // the skip-parse path previously leaked content that isn't real visible text.
                        // Reset so subsequent deltas are calculated correctly.
                        lastPresentedTextLength = stripped.length
                    }

                    // Persist streaming text to ui_messages.json (C3 fix — awaited inline, NOT in launch{}).
                    // First chunk: add partial message. Subsequent: update in-place.
                    // Use stripped text (partial XML tags removed), NOT raw accumulatedText.
                    // The raw text includes XML tool call tags which would show as raw XML on resume.
                    // Use `stripped` (always up-to-date) rather than `visibleText` (stale on skip-parse path).
                    messageStateHandler?.let { handler ->
                        val persistText = stripped
                        if (isFirstStreamChunk) {
                            handler.addToClineMessages(UiMessage(
                                ts = System.currentTimeMillis(),
                                type = UiMessageType.SAY,
                                say = UiSay.TEXT,
                                text = persistText,
                                partial = true
                            ))
                            isFirstStreamChunk = false
                        } else {
                            val msgs = handler.getClineMessages()
                            val lastIdx = msgs.lastIndex
                            if (lastIdx >= 0 && msgs[lastIdx].partial) {
                                handler.updateClineMessage(lastIdx, msgs[lastIdx].copy(text = persistText))
                            }
                        }
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

            // Capture accumulated text for abort persistence (accessible outside the while loop).
            lastAccumulatedText = accumulatedText.toString()

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
                abortStream(lastAccumulatedText, "streaming_failed")
                onDebugLog?.invoke("error", "loop_exit", "Exit: api_retries_exhausted (${apiResult.type.name})", mapOf(
                    "errorType" to apiResult.type.name,
                    "iteration" to iteration,
                    "apiRetryCount" to apiRetryCount,
                    "message" to apiResult.message.take(200)
                ))
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

            // Finalize streaming UI message (flip partial=false) and update api_req_started with cost (I7 fix).
            val promptTokens = response.usage?.promptTokens ?: 0
            val completionTokens = response.usage?.completionTokens ?: 0
            messageStateHandler?.let { handler ->
                // Finalize partial text message
                val msgs = handler.getClineMessages()
                val lastIdx = msgs.lastIndex
                if (lastIdx >= 0 && msgs[lastIdx].partial) {
                    handler.updateClineMessage(lastIdx, msgs[lastIdx].copy(partial = false))
                }

                // Update api_req_started with cost/token info
                if (apiReqStartedIdx >= 0 && apiReqStartedIdx <= msgs.lastIndex) {
                    val costJson = """{"tokensIn":$promptTokens,"tokensOut":$completionTokens}"""
                    handler.updateClineMessage(apiReqStartedIdx,
                        handler.getClineMessages()[apiReqStartedIdx].copy(text = costJson))
                }
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

            // Persist assistant message to api_conversation_history (Cline pattern).
            messageStateHandler?.addToApiConversationHistory(ApiMessage(
                role = ApiRole.ASSISTANT,
                content = buildApiContentBlocks(assistantMessage),
                ts = System.currentTimeMillis(),
                modelInfo = ModelInfo(modelId = brain.modelId),
                metrics = ApiRequestMetrics(inputTokens = promptTokens, outputTokens = completionTokens)
            ))

            val hasToolCalls = !assistantMessage.toolCalls.isNullOrEmpty()
            val hasContent = !assistantMessage.content.isNullOrBlank()

            when {
                // Case A: Tool calls present — execute them
                hasToolCalls -> {
                    consecutiveEmpties = 0
                    consecutiveMistakes = 0  // Tool use resets mistake count
                    brain.temperature = 0.0  // Reset temperature escalation after successful tool use

                    // Real tool call arrived — remove any stale trailing nudge chain that
                    // preceded this turn. The nudges served their purpose; leaving them in
                    // context just trains the next turn on the repeated [ERROR] pattern.
                    contextManager.pruneTrailingNudgePairs(TEXT_ONLY_NUDGE)
                    contextManager.pruneTrailingNudgePairs(EMPTY_RESPONSE_ERROR)

                    // Batch guard: if a completion signal (attempt_completion or task_report)
                    // co-occurs with other tools in the same LLM turn, strip it and nudge.
                    // Reason: the LLM cannot legitimately conclude a task in the same turn it
                    // issued the reads it wants to conclude on — it hasn't seen the results yet.
                    // Letting it complete here makes the summary a speculation, not an observation.
                    val rawCalls = assistantMessage.toolCalls!!
                    val completionTools = setOf("attempt_completion", "task_report")
                    val hasCompletion = rawCalls.any { it.function.name in completionTools }
                    val filteredCalls = if (hasCompletion && rawCalls.size > 1) {
                        val completionName = rawCalls.first { it.function.name in completionTools }.function.name
                        LOG.warn("[Loop] $completionName batched with ${rawCalls.size - 1} other tool(s) — deferring completion to next turn")
                        onDebugLog?.invoke(
                            "warn",
                            "batch_guard",
                            "Dropped $completionName from multi-tool batch (size=${rawCalls.size})",
                            mapOf("batchSize" to rawCalls.size)
                        )
                        rawCalls.filter { it.function.name !in completionTools }
                    } else {
                        rawCalls
                    }

                    val completionResult = executeToolCalls(filteredCalls, iteration)
                    if (completionResult != null) return completionResult

                    // If we stripped the completion signal, nudge the LLM so it re-issues it
                    // after observing the results in the next turn.
                    if (hasCompletion && filteredCalls.size < rawCalls.size) {
                        val completionName = rawCalls.first { it.function.name in completionTools }.function.name
                        contextManager.addUserMessage(
                            "[System] You tried to call $completionName in the same turn as other tool calls. " +
                                "Your summary would be based on guesses, not observations. The other tools have now " +
                                "executed — review their results and call $completionName again on its own."
                        )
                    }
                }

                // Case B: Text only (no tool calls) — Cline pattern: inject nudge, increment mistake count
                // Ported from Cline index.ts line 3201-3207: noToolsUsed + consecutiveMistakeCount++
                // NOTE: We do NOT reset consecutiveEmpties in the general Case B. A text-only response
                // between two empty responses is still part of an empty/stall cycle — resetting the
                // counter allows alternating text-only ↔ empty to cycle past MAX_CONSECUTIVE_EMPTIES.
                // Exception: plan-mode conversational turns (see inner branch below) are genuine user
                // exchanges; both counters are reset there.
                // Only a real tool call (Case A) resets both counters in act mode.
                hasContent -> {
                    consecutiveMistakes++
                    LOG.info("[Loop] Text-only response (no tool calls) — mistake $consecutiveMistakes/$maxConsecutiveMistakes")

                    if (planMode && userInputChannel != null) {
                        // In plan mode, text-only responses are conversational turns.
                        // Signal UI to drop the working spinner — we're idle awaiting user reply.
                        val reason = "Plan-mode reply — waiting for your next message."
                        onDebugLog?.invoke("info", "await_user", reason, null)
                        onAwaitingUserInput?.invoke(reason)
                        contextManager.addUserMessage(withEnvDetails(userInputChannel.receive()))
                        consecutiveMistakes = 0
                        consecutiveEmpties = 0  // reset: plan-mode chat is a genuine exchange, not a stall
                    } else if (consecutiveMistakes >= maxConsecutiveMistakes && userInputChannel != null) {
                        // Cline pattern: at max mistakes, ask user for feedback instead of failing.
                        // Without onAwaitingUserInput the UI would silently spin forever here.
                        LOG.warn("[Loop] Max consecutive mistakes ($maxConsecutiveMistakes) — waiting for user feedback")
                        val reason = "The model keeps replying without using a tool. Send guidance to continue."
                        onDebugLog?.invoke("warn", "await_user", reason, mapOf("consecutiveMistakes" to consecutiveMistakes))
                        onAwaitingUserInput?.invoke(reason)
                        contextManager.addUserMessage(withEnvDetails(userInputChannel.receive()))
                        consecutiveMistakes = 0
                    } else if (consecutiveMistakes >= maxConsecutiveMistakes) {
                        // No user input channel (sub-agent) — fail
                        onDebugLog?.invoke("error", "loop_exit", "Exit: max_consecutive_mistakes (sub-agent, no user channel)", mapOf("max" to maxConsecutiveMistakes))
                        return makeFailed("Agent failed to use tools after $maxConsecutiveMistakes attempts.", iteration)
                    } else {
                        // Below max — inject nudge and continue (Cline: noToolsUsed message).
                        // Collapse any earlier trailing nudge chain first so we never have
                        // more than one "[ERROR] You did not use a tool..." at the tail of
                        // context. Repeated identical nudges can prime the LLM to mimic the
                        // error-response pattern instead of breaking out of it.
                        contextManager.pruneTrailingNudgePairs(TEXT_ONLY_NUDGE)
                        contextManager.addUserMessage(TEXT_ONLY_NUDGE)
                    }
                }

                // Case C: Empty response — provider error (Cline treats separately from text-only)
                // Ported from Cline: empty = provider error, NOT a model mistake.
                // Temperature escalation (OpenHands pattern): bump to 1.0 before the next
                // call to break degenerate zero-temperature sampling that can produce empty
                // outputs in some models. Confirmed safe: Sourcegraph probe result_1 shows
                // temperature 0–1.0 all return HTTP 200.
                else -> {
                    consecutiveEmpties++
                    // Escalate temperature before retry — breaks zero-temp degenerate sampling.
                    // Reset happens in Case A when the model produces a real tool call.
                    brain.temperature = 1.0
                    LOG.warn("[Loop] Empty response from LLM — provider error (attempt $consecutiveEmpties/$MAX_CONSECUTIVE_EMPTIES, temperature escalated to 1.0)")
                    if (consecutiveEmpties >= MAX_CONSECUTIVE_EMPTIES) {
                        onDebugLog?.invoke("error", "loop_exit", "Exit: max_empty_responses ($MAX_CONSECUTIVE_EMPTIES consecutive empties)", mapOf(
                            "consecutiveEmpties" to consecutiveEmpties,
                            "iteration" to iteration
                        ))
                        return makeFailed(
                            "Provider returned $MAX_CONSECUTIVE_EMPTIES consecutive empty responses. Check model/provider configuration.",
                            iteration
                        )
                    }
                    // Same rationale as TEXT_ONLY_NUDGE: collapse any trailing chain first
                    // so identical empty-response errors don't stack in context.
                    contextManager.pruneTrailingNudgePairs(EMPTY_RESPONSE_ERROR)
                    contextManager.addUserMessage(EMPTY_RESPONSE_ERROR)
                }
            }
        }

        if (cancelled.get()) {
            LOG.info("[Loop] Task cancelled at iteration $iteration")
            abortStream(lastAccumulatedText, "user_cancelled")
            onDebugLog?.invoke("info", "loop_exit", "Exit: user_cancelled", mapOf("iteration" to iteration))
            return makeCancelled(iteration)
        }

        LOG.warn("[Loop] Task failed after $iteration iterations: exceeded maximum iterations ($maxIterations)")
        onDebugLog?.invoke("error", "loop_exit", "Exit: max_iterations ($maxIterations)", mapOf("iteration" to iteration))
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
                onDebugLog?.invoke("info", "loop_exit", "Exit: user_cancelled (between tool calls)", mapOf("iteration" to iteration))
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
                    onDebugLog?.invoke("error", "loop_exit", "Exit: doom_loop_hard_limit ($toolName x${loopDetector.currentCount})", mapOf(
                        "tool" to toolName,
                        "repeatCount" to loopDetector.currentCount,
                        "iteration" to iteration
                    ))
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

            val rawTool = toolResolver?.invoke(toolName) ?: tools[toolName]
            val tool = if (rawTool != null && approvalGate != null) ApprovalGatedTool(rawTool, approvalGate) else rawTool
            if (tool == null) {
                // Unknown tool
                val allToolNames = if (toolResolver != null) "use tool_search to find tools" else tools.keys.joinToString(", ")
                val unknownToolMsg = "Unknown tool: '$toolName'. Available tools: $allToolNames"
                fileLogger?.logToolCall(
                    sessionId = sessionId ?: "",
                    toolName = toolName,
                    durationMs = 0,
                    isError = true,
                    errorMessage = unknownToolMsg,
                    tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                )
                sessionMetrics?.recordToolCall(toolName, 0, true)
                reportToolError(call, startTime, unknownToolMsg)
                continue
            }

            // Plan mode guard: block write tools even if the LLM hallucinates them
            if (planMode && toolName in WRITE_TOOLS) {
                val planModeBlockMsg = "Error: '$toolName' is blocked in plan mode. You can only read, search, and analyze code."
                fileLogger?.logToolCall(
                    sessionId = sessionId ?: "",
                    toolName = toolName,
                    durationMs = 0,
                    isError = true,
                    errorMessage = planModeBlockMsg,
                    tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                )
                sessionMetrics?.recordToolCall(toolName, 0, true)
                reportToolError(call, startTime, planModeBlockMsg)
                continue
            }

            // Approval gate (ported from Cline's approval flow).
            // Write tools require user approval unless already allowed for this session.
            // The gate suspends the coroutine (not blocking a thread) until the user responds.
            // Per-tool policy: run_command never gets session-wide approval because each
            // command is arbitrarily different — approving `ls` shouldn't auto-approve `rm -rf /`.
            val policy = ApprovalPolicy.forTool(toolName)
            if (policy.requiresApproval && approvalGate != null && !sessionApprovalStore.isApproved(toolName)) {
                val riskLevel = assessRisk(toolName, call.function.arguments)
                val result = approvalGate.invoke(toolName, call.function.arguments, riskLevel, policy.allowSessionApproval)
                when (result) {
                    ApprovalResult.DENIED -> {
                        val deniedMsg = "Tool execution denied by user."
                        fileLogger?.logToolCall(
                            sessionId = sessionId ?: "",
                            toolName = toolName,
                            durationMs = 0,
                            isError = true,
                            errorMessage = deniedMsg,
                            tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                        )
                        sessionMetrics?.recordToolCall(toolName, 0, true)
                        reportToolError(call, startTime, deniedMsg)
                        continue
                    }
                    ApprovalResult.ALLOWED_FOR_SESSION -> {
                        if (policy.allowSessionApproval) {
                            sessionApprovalStore.approve(toolName)
                        }
                        // If policy doesn't allow session approval, treat as single APPROVED
                    }
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

            // PRE_TOOL_USE hook (ported from Cline's ToolHookUtils.runPreToolUseIfEnabled)
            // Runs before each tool execution; can cancel (block) the tool.
            // Cline: "This should be called by tool handlers after approval succeeds
            //  but before the actual tool execution begins."
            // Task-system tools are hook-exempt (internal bookkeeping, not user-observable).
            if (toolName !in HOOK_EXEMPT && hookManager != null && hookManager.hasHooks(HookType.PRE_TOOL_USE)) {
                val preHookResult = hookManager.dispatch(
                    HookEvent(
                        type = HookType.PRE_TOOL_USE,
                        data = mapOf(
                            "toolName" to toolName,
                            "arguments" to call.function.arguments,
                            "iteration" to iteration,
                            "sessionId" to sessionId,
                            "riskLevel" to assessRisk(toolName, call.function.arguments),
                            "isWriteTool" to (toolName in WRITE_TOOLS).toString(),
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

            // Execute tool (with per-tool timeout and CancellationException propagation)
            val toolResult = try {
                val timeout = tool.timeoutMs
                if (timeout == Long.MAX_VALUE) {
                    tool.execute(params, project)
                } else {
                    withTimeoutOrNull(timeout) {
                        tool.execute(params, project)
                    } ?: ToolResult(
                        content = "Error: Tool '$toolName' timed out after ${timeout / 1000}s. " +
                            "The operation took too long. Try a more specific query or smaller scope.",
                        summary = "Error: timeout after ${timeout / 1000}s",
                        tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                        isError = true
                    )
                }
            } catch (e: CancellationException) {
                throw e  // CRITICAL: Propagate cancellation — never swallow
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

            // Apply LLM-requested output filtering (grep_pattern, output_file)
            var processedContent = toolResult.content
            val grepPattern = (params["grep_pattern"] as? JsonPrimitive)?.contentOrNull
            if (!grepPattern.isNullOrBlank() && processedContent.isNotBlank()) {
                processedContent = ToolOutputConfig.applyGrep(processedContent, grepPattern)
            }

            // Spill to file if requested via output_file=true or if over threshold
            val requestedOutputFile = try {
                params["output_file"]?.jsonPrimitive?.boolean == true
            } catch (_: Exception) { false }

            if (outputSpiller != null && (requestedOutputFile || processedContent.length > ToolOutputConfig.SPILL_THRESHOLD_CHARS)) {
                val spillResult = outputSpiller.spill(toolName, processedContent)
                processedContent = spillResult.preview
            }

            val truncatedContent = truncateOutput(processedContent, tool.outputConfig.maxChars)
            // Re-estimate tokens after processing (grep/spill/truncation) so budget tracking
            // reflects what actually enters context, not the raw tool output.
            val actualTokenEstimate = if (truncatedContent.length < processedContent.length) {
                estimateTokens(truncatedContent)  // Content was truncated — re-estimate
            } else if (processedContent.length < toolResult.content.length) {
                estimateTokens(processedContent)  // Content was grep-filtered or spilled — re-estimate
            } else {
                toolResult.tokenEstimate  // No processing occurred — use original estimate
            }

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
                tokenEstimate = actualTokenEstimate
            )
            sessionMetrics?.recordToolCall(toolName, durationMs, toolResult.isError)
            onDebugLog?.invoke(
                if (toolResult.isError) "error" else "info",
                "tool_call",
                "$toolName ${if (toolResult.isError) "ERROR" else "OK"} (${durationMs}ms)",
                mapOf("tool" to toolName, "duration" to durationMs, "tokens" to actualTokenEstimate)
            )

            // Add result to context
            contextManager.addToolResult(
                toolCallId = toolCallId,
                content = truncatedContent,
                isError = toolResult.isError,
                toolName = toolName
            )

            // Persist tool result to both files (Cline pattern — awaited inline).
            messageStateHandler?.addToApiConversationHistory(ApiMessage(
                role = ApiRole.USER,
                content = listOf(ContentBlock.ToolResult(
                    toolUseId = toolCallId,
                    content = truncatedContent,
                    isError = toolResult.isError
                )),
                ts = System.currentTimeMillis()
            ))
            // Persist UI message — communication tools get semantic types instead of TOOL
            val uiMsg = when (toolName) {
                // plan_mode_respond: persist as PLAN_UPDATE so it renders inline as a plan card on resume.
                // The planData is populated later by onPlanResponse; here we save the markdown text.
                "plan_mode_respond" -> UiMessage(
                    ts = System.currentTimeMillis(),
                    type = UiMessageType.SAY,
                    say = UiSay.PLAN_UPDATE,
                    text = toolResult.content.take(2000),
                    planData = null,
                )
                // ask_followup_question: persist as TEXT — the question is rendered via the
                // showSimpleQuestionCallback (streaming text) or showQuestionsCallback (wizard).
                // On resume, it should appear as agent text, not a tool card.
                "ask_followup_question", "ask_questions" -> {
                    val questionText = try {
                        kotlinx.serialization.json.Json.parseToJsonElement(call.function.arguments)
                            .let { it as? kotlinx.serialization.json.JsonObject }
                            ?.get("question")?.jsonPrimitive?.content
                            ?: toolResult.content
                    } catch (_: Exception) { toolResult.content }
                    UiMessage(
                        ts = System.currentTimeMillis(),
                        type = UiMessageType.SAY,
                        say = UiSay.TEXT,
                        text = questionText.take(2000),
                    )
                }
                // attempt_completion / task_report: persist as ASK/COMPLETION_RESULT for CompletionCard on resume.
                "attempt_completion", "task_report" -> UiMessage(
                    ts = System.currentTimeMillis(),
                    type = UiMessageType.ASK,
                    ask = UiAsk.COMPLETION_RESULT,
                    text = toolResult.content.take(2000),
                    completionData = toolResult.completionData,
                )
                // All other tools: persist as TOOL with full toolCallData
                else -> UiMessage(
                    ts = System.currentTimeMillis(),
                    type = UiMessageType.SAY,
                    say = UiSay.TOOL,
                    text = "$toolName: ${toolResult.summary.take(500)}",
                    toolCallData = ToolCallData(
                        toolCallId = toolCallId,
                        toolName = toolName,
                        args = call.function.arguments,
                        status = if (toolResult.isError) ToolCallStatus.ERROR else ToolCallStatus.COMPLETED,
                        result = toolResult.summary.take(500),
                        output = toolResult.content.takeIf { it != toolResult.summary }?.take(2000),
                        durationMs = durationMs,
                        diff = toolResult.diff,
                        isError = toolResult.isError,
                    ),
                )
            }
            messageStateHandler?.addToClineMessages(uiMsg)

            // POST_TOOL_USE hook (ported from Cline's PostToolUse hook)
            // Observation-only: runs after tool execution, cannot change the result.
            // Cline: fires PostToolUse with tool name, parameters, result, success, durationMs.
            // Task-system tools are hook-exempt (internal bookkeeping, not user-observable).
            if (toolName !in HOOK_EXEMPT && hookManager != null && hookManager.hasHooks(HookType.POST_TOOL_USE)) {
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


            when (toolResult.type) {
                is ToolResultType.Completion -> {
                    LOG.info("[Loop] Task completed in $iteration iterations ($totalInputTokens input, $totalOutputTokens output tokens)")
                    // COMPLETION_RESULT already persisted at Site 1 (when toolName switch above).
                    onDebugLog?.invoke("info", "loop_exit", "Exit: $toolName", mapOf("iteration" to iteration))
                    toolResult.completionData?.let { sessionMetrics?.recordCompletion(it.kind) }
                    return LoopResult.Completed(
                        summary = toolResult.content,
                        iterations = iteration,
                        tokensUsed = totalTokensUsed,
                        completionData = toolResult.completionData,
                        inputTokens = totalInputTokens,
                        outputTokens = totalOutputTokens,
                        filesModified = filesModifiedList(),
                        linesAdded = totalLinesAdded,
                        linesRemoved = totalLinesRemoved
                    )
                }
                is ToolResultType.SessionHandoff -> {
                    val handoff = toolResult.type
                    onDebugLog?.invoke("info", "loop_exit", "Exit: new_task_session_handoff", mapOf("iteration" to iteration))
                    return LoopResult.SessionHandoff(
                        context = handoff.context,
                        iterations = iteration,
                        tokensUsed = totalTokensUsed,
                        inputTokens = totalInputTokens,
                        outputTokens = totalOutputTokens,
                        filesModified = filesModifiedList(),
                        linesAdded = totalLinesAdded,
                        linesRemoved = totalLinesRemoved
                    )
                }
                is ToolResultType.PlanResponse -> {
                    val pr = toolResult.type
                    LOG.info("[Loop] Plan presented (needsMoreExploration=${pr.needsMoreExploration})")
                    onPlanResponse?.invoke(toolResult.content, pr.needsMoreExploration)

                    if (!pr.needsMoreExploration && userInputChannel != null) {
                        // Wait for user input (matches Cline's ask() pattern).
                        // The user can: type in chat, add step comments, or click approve.
                        // Each sends a message into the channel, which resumes the loop.
                        contextManager.addUserMessage(withEnvDetails(userInputChannel.receive()))
                        // Continue the loop — LLM will see the user's message and respond
                    }
                    // needs_more_exploration=true OR no channel: loop continues immediately
                }
                is ToolResultType.PlanModeToggle -> {
                    LOG.info("[Loop] Plan mode enabled by LLM via enable_plan_mode tool")
                    onPlanModeToggle?.invoke(true)
                }
                is ToolResultType.PlanDiscarded -> {
                    LOG.info("[Loop] Plan discarded by LLM via discard_plan tool")
                    onPlanDiscarded?.invoke()
                }
                is ToolResultType.SkillActivation -> {
                    val activation = toolResult.type
                    contextManager.setActiveSkill(activation.skillContent)
                }
                is ToolResultType.Standard, is ToolResultType.Error -> {
                    // Normal result — no special dispatch
                }
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
     * Persist abort state when the stream is interrupted mid-flight.
     *
     * Port of Cline's abortStream (task-index.ts:2721-2780):
     * 1. Flip the last partial UI message to non-partial so it renders correctly on resume
     * 2. Append a synthetic assistant turn with an interrupt marker so the LLM knows the
     *    previous response was cut short
     * 3. Save both files atomically
     *
     * Called from two paths:
     * - User cancellation: cancelReason = "user_cancelled"
     * - API error after retries exhausted: cancelReason = "streaming_failed"
     */
    private suspend fun abortStream(assistantText: String, cancelReason: String) {
        messageStateHandler?.let { handler ->
            // 1. Flip last partial message to non-partial
            val msgs = handler.getClineMessages()
            val lastIdx = msgs.lastIndex
            if (lastIdx >= 0 && msgs[lastIdx].partial) {
                handler.updateClineMessage(lastIdx, msgs[lastIdx].copy(partial = false))
            }

            // 2. Append synthetic assistant turn with interrupt marker
            val interruptMarker = if (cancelReason == "streaming_failed") {
                "[Response interrupted by API Error]"
            } else {
                "[Response interrupted by user]"
            }
            handler.addToApiConversationHistory(ApiMessage(
                role = ApiRole.ASSISTANT,
                content = listOf(ContentBlock.Text("$assistantText\n\n$interruptMarker")),
                ts = System.currentTimeMillis()
            ))

            // 3. Save both files
            handler.saveBoth()
        }
    }

    /**
     * Append the latest environment_details block to a user message.
     * Returns the message unchanged if the provider returns null.
     */
    private fun withEnvDetails(message: String): String {
        val envDetails = environmentDetailsProvider?.invoke()
        return if (envDetails != null) "$message\n\n$envDetails" else message
    }

    /**
     * Convert a ChatMessage (assistant response) to ApiMessage ContentBlock list.
     * Preserves text content and tool_use calls for lossless api_conversation_history (C2 fix).
     */
    private fun buildApiContentBlocks(msg: ChatMessage): List<ContentBlock> {
        val blocks = mutableListOf<ContentBlock>()
        val textContent = msg.content
        if (!textContent.isNullOrBlank()) {
            blocks.add(ContentBlock.Text(textContent))
        }
        msg.toolCalls?.forEach { tc ->
            blocks.add(ContentBlock.ToolUse(
                id = tc.id,
                name = tc.function.name,
                input = tc.function.arguments
            ))
        }
        return blocks.ifEmpty { listOf(ContentBlock.Text("")) }
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

package com.workflow.orchestrator.agent.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.workflow.orchestrator.agent.AgentService
import com.workflow.orchestrator.agent.hooks.HookEvent
import com.workflow.orchestrator.agent.hooks.HookResult
import com.workflow.orchestrator.agent.hooks.HookType
import com.workflow.orchestrator.agent.loop.ApprovalResult
import com.workflow.orchestrator.agent.loop.ContextManager
import com.workflow.orchestrator.agent.loop.FailureReason
import com.workflow.orchestrator.agent.loop.LoopResult
import com.workflow.orchestrator.agent.loop.PlanJson
import com.workflow.orchestrator.agent.loop.SessionApprovalStore
import com.workflow.orchestrator.agent.loop.ToolCallProgress
import com.workflow.orchestrator.agent.loop.queue.QueuedMessage
import com.workflow.orchestrator.agent.loop.queue.QueueSourceKind
import com.workflow.orchestrator.agent.loop.queue.UserQueuePolicy
import com.workflow.orchestrator.agent.monitor.MonitorPool
import com.workflow.orchestrator.agent.observability.HaikuPhraseGenerator
import com.workflow.orchestrator.agent.observability.PhraseActivityGate
import com.workflow.orchestrator.agent.session.HistoryItem
import com.workflow.orchestrator.agent.session.MessageStateHandler
import com.workflow.orchestrator.agent.session.PlanApprovalData
import com.workflow.orchestrator.agent.session.ResumeHelper
import com.workflow.orchestrator.agent.session.UiAsk
import com.workflow.orchestrator.agent.session.UiMessage
import com.workflow.orchestrator.agent.session.UiMessageType
import com.workflow.orchestrator.agent.session.UiSay
import com.workflow.orchestrator.agent.settings.AgentSettings
import com.workflow.orchestrator.agent.settings.ToolPreferences
import com.workflow.orchestrator.agent.tools.ArtifactRenderResult
import com.workflow.orchestrator.agent.tools.CompletionData
import com.workflow.orchestrator.agent.tools.CompletionKind
import com.workflow.orchestrator.agent.tools.background.BackgroundPool
import com.workflow.orchestrator.agent.tools.builtin.ArtifactResultRegistry
import com.workflow.orchestrator.agent.tools.builtin.Question
import com.workflow.orchestrator.agent.tools.builtin.RunCommandTool
import com.workflow.orchestrator.agent.tools.cancel.ToolStopCoordinator
import com.workflow.orchestrator.agent.tools.subagent.SubagentExecutionStatus
import com.workflow.orchestrator.agent.tools.subagent.SubagentProgressUpdate
import com.workflow.orchestrator.agent.ui.plan.AgentPlanEditor
import com.workflow.orchestrator.agent.ui.plan.AgentPlanVirtualFile
import com.workflow.orchestrator.agent.util.JsEscape
import com.workflow.orchestrator.core.events.BackgroundProcessSnapshotDto
import com.workflow.orchestrator.core.events.EventBus
import com.workflow.orchestrator.core.events.MonitorSnapshotDto
import com.workflow.orchestrator.core.events.WorkflowEvent
import com.workflow.orchestrator.core.settings.effectiveAcceptWindowMs
import com.workflow.orchestrator.core.util.PathLinkResolver
import com.workflow.orchestrator.core.util.ProjectIdentifier
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Outcome of routing an accepted inbound cross-IDE delegation into IDE-B's agent tab.
 *
 * - [STARTED] — a foreground session is running now (idle tab) or was started by the human
 *   clicking Start within the busy-case accept window.
 * - [DECLINED_TIMEOUT] — the tab was busy and the human did not click Start within the
 *   accept window, so the delegation was declined (no background execution).
 */
enum class DelegatedStartOutcome { STARTED, DECLINED_TIMEOUT }

/**
 * Self-describing descriptor of the task IDE-B is busy with when it declines an incoming
 * delegation (PART 2 — busy-enrichment). Carried out of the busy gate so the inbound
 * decline reply can NAME the in-flight task and — critically — echo the delegator session
 * id, so IDE-A can recognize the blocker as ITS OWN earlier task.
 *
 * All fields are nullable/best-effort: when IDE-B can't resolve a field, the inbound reason
 * composer falls back to the generic wording and nothing regresses.
 *
 * - [inFlightSessionId]            — IDE-B's currently-running local session id.
 * - [inFlightTitle]               — that session's human title (HistoryItem.task), if known.
 * - [inFlightDelegatorSessionId]  — when the in-flight session is ITSELF a delegated one,
 *                                   the delegator session id that originated it (so IDE-A can
 *                                   match it against a handle it holds). Null when the in-flight
 *                                   session is a local (non-delegated) one.
 * - [inFlightDelegatorRepo]       — the delegator repo that originated the in-flight delegated
 *                                   session, if any.
 */
data class BusyInfo(
    val inFlightSessionId: String?,
    val inFlightTitle: String?,
    val inFlightDelegatorSessionId: String?,
    val inFlightDelegatorRepo: String?,
)

/**
 * Busy-path accept-window wait, factored out of [AgentController.startDelegatedSession] so the
 * timing core is unit-testable headless (review finding I4) — no Project/Application/EDT, and
 * kotlinx-coroutines-test virtual time advances [windowMs] deterministically.
 *
 * Suspends until [gate] completes (the human clicked Start → true) or [windowMs] elapses
 * (→ false, declined-by-timeout). Returns the gate's value when it wins the race, else false.
 */
internal suspend fun awaitIncomingStart(
    gate: kotlinx.coroutines.CompletableDeferred<Boolean>,
    windowMs: Long,
): Boolean = kotlinx.coroutines.withTimeoutOrNull(windowMs) { gate.await() } == true

/**
 * Whether an INCOMING cross-IDE delegation should be treated as "busy" (→ QUEUE_INCOMING) on
 * IDE-B. Factored out of [AgentController.startDelegatedSession] so the decision is unit-testable
 * headless (Bug B regression — `IncomingDelegationBusyGateTest`).
 *
 * "Busy" means *an agent loop is actively running right now* — i.e. [jobActive]. It deliberately
 * does NOT consider [sessionLoaded]: a session that merely sits LOADED in the tab after completing
 * (delegated or interactive) is NOT busy. Counting a loaded-but-idle session as busy was the
 * works-once bug — a completed delegated session never clears `currentSessionId` (only
 * [resetForNewChat] does), so the second delegation got stuck in the human-Start accept window and
 * timed out as `declined_timeout`.
 *
 * [sessionLoaded] is accepted (and ignored) so the call site reads self-documentingly and so a
 * future policy change has the signal in hand without re-threading it.
 */
internal fun decideIncomingBusy(jobActive: Boolean, @Suppress("UNUSED_PARAMETER") sessionLoaded: Boolean): Boolean =
    jobActive

/**
 * Bridges the JCEF chat dashboard to [AgentService].
 *
 * Responsibilities:
 * - Routes user actions (send, cancel, new chat, plan toggle) from the dashboard to the service
 * - Wires streaming text, tool call progress, and completion callbacks back to the dashboard
 * - Owns a [ContextManager] for multi-turn conversation within a chat session
 * - Manages mirror panels (e.g. "View in Editor" editor tabs)
 */
class AgentController(
    private val project: Project,
    private val dashboard: AgentDashboardPanel
) : Disposable {
    companion object {
        private val LOG = Logger.getInstance(AgentController::class.java)

        /**
         * How long IDE-B waits for the human to click Start on a busy-tab incoming delegation
         * before declining it. MUST stay STRICTLY LESS than IDE-A's
         * `DelegationClient.connectAndAwaitAccept` `acceptTimeoutMillis` (60_000L): on a Start
         * clicked near the deadline, IDE-B still has to launch the session and send its
         * AcceptResult, and that round-trip must land before IDE-A gives up. The 5s gap
         * (55_000L vs 60_000L) is the framing/scheduling buffer; if equal, a late Start could
         * make IDE-B's reply land on an already-dead channel.
         */
        const val ACCEPT_WINDOW_MS = 55_000L

        /**
         * Upper bound on awaiting the started session id (review finding C1). `runDelegatedNow`
         * launches the session fire-and-forget on `controllerScope.launch(Dispatchers.EDT)`; if
         * that EDT coroutine throws before `onSessionStarted` fires (e.g. `resetForNewChat()`
         * throws, or the scope is cancelled on project close), the sid deferred never completes.
         * `handleConnect` bounds its await with this so the socket coroutine can't hang forever
         * holding IDE-A's channel open. Session start is near-instant on the idle path, so 15s is
         * generous.
         */
        const val SESSION_START_TIMEOUT_MS = 15_000L

        /** Tool window hosting the Agent tab — id registered by `WorkflowToolWindowFactory`. */
        private const val WORKFLOW_TOOL_WINDOW_ID = "Workflow"

        /**
         * Test-only seam — constructs a minimal controller from a mocked
         * dashboard and delegates to the **real** [handleSteeringDrained]
         * implementation. Avoids the parallel-re-implementation drift risk
         * that a separate `flushStreamBuffer/finalizeToolChain/promote`
         * sequence in the companion would create: if a future change reorders
         * those three calls in the production method, every call site
         * (including this test) picks up the change automatically.
         */
        internal fun invokeOnSteeringDrainedForTest(
            dashboard: AgentDashboardPanel,
            drainedIds: List<String>,
        ) {
            handleSteeringDrainedWithDashboard(dashboard, drainedIds)
        }

        /**
         * Test-only seam mirroring the post-approval resume flush. Delegates
         * to the same private static helper [handleApprovalResumeFlushWithDashboard]
         * the production `approvalGate` invokes when an approval resolves to
         * APPROVED / ALLOWED_FOR_SESSION.
         */
        internal fun invokeOnApprovalResumeForTest(dashboard: AgentDashboardPanel) {
            handleApprovalResumeFlushWithDashboard(dashboard)
        }

        /**
         * Single source of truth for the steering-drain UI flush sequence.
         * Both `onSteeringDrained` lambdas in [executeTask] and [resumeTask]
         * call this — directly, via [handleSteeringDrained] — and the test
         * exercises it via [invokeOnSteeringDrainedForTest]. Keep this method
         * the only place the three-call order is defined.
         *
         * Bug 2: flush in-flight stream so the next iteration's tokens start
         * a fresh bubble rather than concatenating onto the pre-drain text.
         * Bug 1: finalize active tool calls so the promoted user message
         * lands BELOW them in visual order. The JS-side endStream/finalize
         * are idempotent so this is safe even on text-only iterations with
         * no active tools.
         */
        private fun handleSteeringDrainedWithDashboard(
            dashboard: AgentDashboardPanel,
            drainedIds: List<String>,
        ) {
            dashboard.flushStreamBuffer()
            dashboard.finalizeToolChain()
            dashboard.promoteQueuedSteeringMessages(drainedIds)
        }

        /**
         * Single source of truth for the post-approval resume flush. Called
         * by [approvalGate] when the result is APPROVED or ALLOWED_FOR_SESSION,
         * and by the test seam.
         */
        private fun handleApprovalResumeFlushWithDashboard(dashboard: AgentDashboardPanel) {
            dashboard.flushStreamBuffer()
        }
    }

    /**
     * Instance-level wrapper around [handleSteeringDrainedWithDashboard] so
     * the two `onSteeringDrained` lambdas don't have to plumb [dashboard]
     * through manually. Test code uses the companion static directly.
     */
    private fun handleSteeringDrained(drainedIds: List<String>) {
        handleSteeringDrainedWithDashboard(dashboard, drainedIds)
    }

    /**
     * Instance-level wrapper around [handleApprovalResumeFlushWithDashboard]
     * for use inside [approvalGate].
     */
    private fun handleApprovalResumeFlush() {
        handleApprovalResumeFlushWithDashboard(dashboard)
    }

    private val service = AgentService.getInstance(project)

    /**
     * Per-project [AskQuestionsTool] instance — resolved lazily from the [ToolRegistry]
     * so each IDE window's AgentController wires callbacks to its OWN tool instance.
     * This is the fix for the cross-IDE question routing bug: when fields lived on the
     * companion object, the last controller to initialise would overwrite all other
     * windows' callbacks.
     */
    private val askQuestionsTool: com.workflow.orchestrator.agent.tools.builtin.AskQuestionsTool by lazy {
        service.registry.get("ask_followup_question") as com.workflow.orchestrator.agent.tools.builtin.AskQuestionsTool
    }

    private var contextManager: ContextManager? = null
    /** Session-scoped approval store. Created on first message, cleared on newChat. */
    private val sessionApprovalStore = SessionApprovalStore()
    private var currentJob: Job? = null
    private var taskStartTime: Long = 0L

    // Accumulated subagent token usage — added to parent session totals in updateSessionStats.
    @Volatile private var subagentAccumIn = 0L
    @Volatile private var subagentAccumOut = 0L
    // Last stats pushed by the parent AgentLoop; re-pushed when subagents complete.
    private data class SessionStatsSnapshot(val modelId: String, val tokensIn: Long, val tokensOut: Long, val costUsd: Double?)
    @Volatile private var lastParentStats: SessionStatsSnapshot? = null
    /** Last task text for retry button (may include XML mention context). Gap 17. */
    private var lastTaskText: String? = null
    /** Clean display text for retry/restore (without XML). Null = same as lastTaskText. */
    private var lastDisplayText: String? = null
    /** The original task text from the very first user message in this session. "First message wins" —
     *  plan-mode approvals and steering injections never overwrite this. Used to re-inject the original
     *  task when context is cleared on plan approval so the LLM retains full intent. */
    private var originalTaskText: String? = null
    /** Mentions JSON for retry display chips. */
    private var lastDisplayMentionsJson: String? = null
    /**
     * Channel for feeding user input into a running loop.
     * Used in plan mode: after the LLM presents a plan, the loop waits on this channel.
     * User messages, plan comments, and approve actions all send into this channel.
     * Matches Cline's ask() pattern where the loop suspends until user responds.
     */
    private var userInputChannel: Channel<String>? = null
    /** True when the loop is actively waiting for user input (plan presented, not exploring). */
    private var loopWaitingForInput = false
    /**
     * Typed UI message to persist when the loop next receives from [userInputChannel].
     * Set in the [loopWaitingForInput] branch of [executeTask] when a [uiMessageOverride]
     * is provided (e.g. a PLAN_APPROVED bubble from [handleApprovalChoice]).
     * Consumed once by the [onUserInputReceived] callback wired into [AgentService.executeTask],
     * then cleared atomically via [java.util.concurrent.atomic.AtomicReference.getAndSet].
     * Cleared in all cleanup paths (cancel, newChat, resumeSession) to prevent stale override
     * from polluting the next session.
     */
    private val pendingUiMessageOverride = java.util.concurrent.atomic.AtomicReference<com.workflow.orchestrator.agent.session.UiMessage?>(null)

    /**
     * Image attachments the user added to a plan-mode/feedback reply, set just
     * before the typed answer is sent into [userInputChannel]. Drained once by the
     * [AgentLoop] (via the `pendingChannelImageRefs` provider wired into
     * [AgentService.executeTask]/[resumeSession]) when it consumes the channel
     * message, so the reply's images reach the LLM as `ContentPart.Image` parts.
     * Single-use: the loop's provider does `getAndSet(emptyList())`.
     */
    private val pendingReplyImageRefs = java.util.concurrent.atomic.AtomicReference<List<com.workflow.orchestrator.agent.session.ContentBlock.ImageRef>>(emptyList())

    /**
     * Task 2.1: queue ownership moved to AgentService (per-session, persistence-backed).
     * Resolve via [com.workflow.orchestrator.agent.AgentService.queueForSession] for the active
     * session. Returns null when no session is active (safe — callers guard on currentSessionId).
     */
    private fun activeSessionQueue() = currentSessionId?.let { service.queueForSession(it) }

    private val steeringCounter = java.util.concurrent.atomic.AtomicLong(0)

    /**
     * Pending approval deferred — the agent loop suspends on this while
     * waiting for the user to approve/deny a write tool execution.
     * Completed by the JCEF approval card callbacks.
     *
     * **Concurrency invariant:** only one approval gate is ever pending at
     * a time, because:
     *  1. Within a single orchestrator session, `AgentLoop.executeToolCalls`
     *     walks `toolCalls` sequentially — each `approvalGate.invoke(...)`
     *     call fully `await()`s before the next iteration runs.
     *  2. Subagents spawned via [SpawnAgentTool] → [SubagentRunner] construct
     *     their `AgentLoop` **without** an `approvalGate`, so parallel
     *     research subagents never touch this field.
     *
     * If this invariant is ever violated (e.g. someone wires `approvalGate =
     * ::approvalGate` into `SubagentRunner`) the reentry will be caught by
     * the guarded log warning in [approvalGate]. Before restoring that
     * wiring, convert this field to a `ConcurrentHashMap<String,
     * CompletableDeferred<ApprovalResult>>` keyed by toolCallId.
     */
    private var pendingApproval: CompletableDeferred<ApprovalResult>? = null

    /**
     * The tool name associated with the current pending approval.
     * Used when the user clicks "Allow for Session" — we need to know which tool.
     */
    private var pendingApprovalToolName: String? = null

    /**
     * Current session ID -- tracked so resume can reference it.
     * Set when executeTask creates or resumes a session, cleared on newChat.
     */
    private var currentSessionId: String? = null

    /**
     * File-attachment ingest service (Task 8 / Plan 2026-05-27).
     * Wired once in [init] after [panel] callbacks are set up.
     * Null until the init block has run (safe: all callers are post-init).
     */
    private var attachmentIngest: AttachmentIngestService? = null

    /**
     * True once any session-entry path has started/resumed/forked a session. Replaces the
     * old `contextManager == null` heuristic for deciding whether a user message starts a
     * brand-new chat (which wiped the view). Reset only by [resetForNewChat].
     *
     * `@Volatile`: written from the IO coroutine (via `onSessionStarted` on the handoff path)
     * and read on the EDT (in `handleUserMessage`). A plain `var` could let the EDT see a
     * stale `false` and wrongly wipe the view. (Review blocking item #1.)
     */
    @Volatile
    private var sessionActive = false

    /**
     * Session ID being viewed (read-only) from the history panel.
     * Set by [showSession], cleared when the user starts a new chat or resumes.
     * The user must explicitly click "Resume" to start execution.
     */
    private var viewedSessionId: String? = null

    /**
     * Monotonic generation for [showSession]'s async push (W4-B2 review Important #1).
     *
     * The push-time `viewedSessionId != sessionId` check alone cannot catch navigations
     * that change the view WITHOUT setting a new viewedSessionId — [showHistory] leaves
     * it untouched, so a late showSession load would yank the user out of the history
     * view they just navigated to. [showSession] increments + captures the generation on
     * EDT phase 1 and compares the captured value at push time; [showHistory] and
     * [resetForNewChat] increment it (inline — no new functions between the
     * showSession/resumeViewedSession source-test sentinels), invalidating any in-flight
     * push. AtomicLong because the IO coroutine reads it while the EDT writes it.
     */
    private val showSessionGeneration = java.util.concurrent.atomic.AtomicLong(0L)

    /**
     * Structured plan data from the last plan_mode_respond call.
     * Populated in [onPlanResponse] and used by [openPlanInEditor] to open the plan
     * in a full JCEF editor tab.
     */
    private var currentPlanData: PlanJson? = null
    /** Accumulates plan markdown across append=true plan_mode_respond calls for this session. Cleared on session reset. */
    private var accumulatedPlanText: String = ""

    /**
     * True while the programmatic "Approve & Clear Context" / "Just Approve" question
     * is showing. Routes the question-submitted callback to [handleApprovalChoice]
     * instead of the normal [AskQuestionsTool] resolution path.
     */
    private var pendingApprovalChoice = false

    // ── Live question metadata — cached when the wizard is shown, cleared on cancel/submit ──

    private enum class QuestionMode { SIMPLE, WIZARD }
    private data class LiveQuestions(val mode: QuestionMode, val questions: List<Question>)

    /**
     * Cached question metadata for the currently-shown ask_followup_question wizard.
     *
     * Set when the question wizard is rendered (simple or wizard mode).
     * Used on submit to produce an enriched payload (question text + label resolution)
     * instead of raw synthetic option IDs.
     * Cleared on cancel/skip to prevent stale data bleeding into subsequent questions.
     */
    @Volatile private var liveQuestions: LiveQuestions? = null

    /**
     * Per-wizard accumulated answers. Hoisted from `wireCallbacks` so the
     * setCefQuestionCallbacks closure can be invoked on both the primary
     * dashboard panel AND any mirror panels ("View in Editor" tab) without
     * each panel having its own private answer map. All access is on EDT
     * (JBCefJSQuery handlers run on EDT), so a regular MutableMap is safe.
     */
    private val collectedAnswers = mutableMapOf<String, String>()
    private val skippedQuestionIds = mutableSetOf<String>()

    /** Reusable lenient JSON instance for parsing question metadata. */
    private val lenientJson = Json { ignoreUnknownKeys = true }

    /** Recent tool calls for Haiku phrase context (FIFO, max 3). */
    private val recentToolCalls = mutableListOf<Pair<String, String>>()

    /** Coroutine job for the 30s Haiku phrase timer. */
    private var phraseTimerJob: Job? = null

    /** Current Haiku-generated session title (null until first generation). */
    private var currentHaikuTitle: String? = null

    /** Last LLM stream text snippet — gives Haiku context about what the agent is thinking. */
    @Volatile private var lastStreamSnippet: String = ""

    /**
     * Controller-scoped coroutine scope for long-lived subscriptions (e.g. EventBus.events).
     * Cancelled in [dispose] so subscriptions stop when the controller goes away.
     */
    private val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Pending busy-case incoming delegations awaiting a human Start click. Keyed by the
     * delegation `key` (delegator session id). The [CompletableDeferred] is completed with
     * `true` by [startIncomingDelegation] when the user clicks Start within the accept
     * window, or left to time out (handled by `withTimeoutOrNull` in [startDelegatedSession]).
     * No background execution — a delegated session runs only while it is the focused session,
     * so a busy tab must surface a top-bar prompt and wait for the human rather than auto-run.
     */
    private val pendingIncomingStarts =
        java.util.concurrent.ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    /**
     * Atomic single-slot reservation that closes BUG #3's check-then-act gap: the incoming-delegation
     * busy gate ([decideIncomingBusy]) reads the LIVE job, but `currentJob` is assigned LATER on the
     * fire-and-forget EDT coroutine inside [runDelegatedNow] / [runResumedDelegatedNow] (via
     * `onJobCreated`). `DelegationServer.acceptLoop` launches inbound handlers WITHOUT joining, so two
     * near-simultaneous inbound delegations BOTH read `currentJob` inactive → BOTH RUN_NOW, and the
     * second's `resetForNewChat` cancels the first's just-started session.
     *
     * [startDelegatedSession] / [resumeDelegatedSession] CLAIM this reservation atomically (folding in
     * the job-based busy verdict) BEFORE launching the runner, so exactly ONE of N concurrent inbound
     * delegations proceeds to RUN_NOW; the rest take QUEUE_INCOMING / DECLINED_TIMEOUT. The runners
     * RELEASE it once `currentJob` is actually assigned (`onJobCreated`) AND on every failure /
     * exception path so a failed start can never wedge the gate closed. Pure contract pinned by
     * `DelegationStartReservationTest`.
     */
    private val startReservation =
        com.workflow.orchestrator.agent.delegation.DelegationStartReservation()

    /** Coalesces rapid-fire stream chunks into ~16ms batched bridge dispatches. */
    private val streamBatcher = StreamBatcher(
        onFlush = { batched -> dashboard.appendStreamToken(batched) }
    )

    /**
     * P1-12: coalesces THINKING deltas the way [streamBatcher] coalesces prose. Thinking
     * models emit thousands of `<thinking>` chunks per response; pre-fix each chunk fired
     * one `dashboard.appendToThinking` → one JCEF executeJavaScript. Lifecycle is lockstep
     * with [streamBatcher]/[thinkingSplitter]: flushed in [flushStream], cleared in
     * [clearStream], disposed with the controller.
     *
     * The invoker is EDT-INLINE on purpose: on block close, [dispatchSplitParts] posts ONE
     * EDT runnable that runs `flush()` then `endThinking()` — final drain and close
     * execute in the same EDT event, so the tail delta can never be overtaken. (A
     * separate flush-then-post pair was the W4-B3 review hole: an EDT timer tick could
     * have drained the buffer without delivering yet, letting the close land first.)
     */
    private val thinkingStreamBatcher = StreamBatcher(
        onFlush = { batched -> dashboard.appendToThinking(batched) },
        invoker = { block ->
            if (javax.swing.SwingUtilities.isEventDispatchThread()) block() else invokeLater { block() }
        }
    )

    /**
     * Splits inline `<thinking>...</thinking>` blocks out of the assistant text
     * so they render via the prompt-kit Reasoning collapsible (`<ThinkingView>`)
     * instead of as raw XML in the chat. Lockstep with [streamBatcher] — flushed
     * before the batcher and reset alongside it.
     */
    private val thinkingSplitter = ThinkingTagSplitter()
    /** Epoch-ms when the first ThinkingDelta of the current block arrived; 0 when no block is active. */
    private var thinkingBlockStartedAt: Long = 0L

    /** Routes per-tool-call process output chunks to the tool's own Terminal block in the chat UI. */
    private val toolStreamBatcher = PerToolStreamBatcher(
        onFlush = { id, batched ->
            // Log only the first flush per tool call ID to confirm data is flowing, without spamming on every chunk.
            if (firstFlushSeen.add(id)) LOG.info("run_command[$id]: first flush to UI (${batched.length} chars)")
            dashboard.appendToolOutput(id, batched)
        }
    )
    private val firstFlushSeen = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * P1-12: coalesces per-sub-agent THINKING deltas, keyed by agentId (up to 5 parallel
     * sub-agents). Sub-agent PROSE is already batched at the source
     * (`SubagentRunner.textBatcher`, 16ms, flushed before the terminal progress event),
     * but thinking deltas were forwarded per SSE chunk — one `invokeLater` + one
     * executeJavaScript each (see the fast path in [onSubagentProgress]).
     *
     * The invoker is EDT-INLINE on purpose: `flush(agentId)` is called from inside
     * [onSubagentProgress]'s EDT block right before `endSubAgentThinking` / the
     * completion card, and the tail delta must be delivered BEFORE the next statement —
     * a re-posted invokeLater delivery would land after it. Timer ticks also run on the
     * EDT, so the inline branch is correct for them too.
     */
    private val subAgentThinkingBatcher = PerToolStreamBatcher(
        onFlush = { agentId, batched -> dashboard.appendSubAgentThinking(agentId, batched) },
        invoker = { block ->
            if (javax.swing.SwingUtilities.isEventDispatchThread()) block() else invokeLater { block() }
        }
    )

    /**
     * Resolves @file, @folder, @symbol, @tool, /skill, #ticket mentions into rich context for the LLM.
     *
     * Auto-activation: when a mention implies a deferred tool (e.g. `#TICKET-123`
     * implies `jira`), the builder fires `onActivateTool` so the tool's schema
     * is part of the next API call without forcing the LLM into a discovery
     * `tool_search` round-trip first. Registry's `activateDeferred` is
     * `@Synchronized` and idempotent — safe to call repeatedly.
     */
    private val mentionContextBuilder = MentionContextBuilder(
        project = project,
        onActivateTool = { toolName ->
            val activated = service.registry.activateDeferred(toolName)
            if (activated != null) {
                LOG.info("AgentController: auto-activated deferred tool '$toolName' from mention context")
            }
        }
    )

    /** Shared provider — set once from AgentTabProvider, reused for mirrors. */
    private var sharedMentionSearchProvider: MentionSearchProvider? = null

    /** Wire the shared mention search provider so context builder can reuse cached ticket data. */
    fun setMentionSearchProvider(provider: MentionSearchProvider) {
        sharedMentionSearchProvider = provider
        mentionContextBuilder.mentionSearchProvider = provider
    }

    init {
        Disposer.register(this, streamBatcher)
        Disposer.register(this, thinkingStreamBatcher)
        Disposer.register(this, toolStreamBatcher)
        Disposer.register(this, subAgentThinkingBatcher)
        wireCallbacks()
        // Register the push callback used by RenderArtifactTool → ArtifactResultRegistry
        // to forward interactive artifacts into the webview. The tool drives the full
        // async render round-trip through the registry; this callback is the only
        // outbound hop (Kotlin → webview). The result postback comes back via the
        // _reportArtifactResult JCEF bridge registered in AgentCefPanel.
        ArtifactResultRegistry.getInstance(project).setPushCallback { payload ->
            invokeLater {
                dashboard.renderArtifact(payload.title, payload.source, payload.renderId)
            }
        }

        // Subscribe to TaskChanged events emitted by TaskCreateTool / TaskUpdateTool.
        // On each event, fetch the current snapshot of the task from TaskStore and push
        // it to the webview. Also refresh execution steps so the PlanProgressWidget
        // (which reads chatStore.tasks) stays in sync with authoritative state.
        subscribeToTaskChanges()

        // Subscribe to BackgroundChanged events so the top-bar background indicator
        // stays up-to-date as processes are registered, complete, or killed.
        subscribeToBackgroundChanges()

        // Subscribe to MonitorChanged events so the top-bar monitor indicator
        // stays up-to-date as monitors are registered, stopped, or exit.
        subscribeToMonitorChanges()

        // Subscribe to background-process completion events so the user sees a
        // visible status bubble in the chat when a background process finishes.
        // AgentService already routes completions into the loop's steering queue
        // (delivering the completion into the LLM context) — this adds the matching
        // human-visible message in the chat transcript alongside.
        subscribeToBackgroundCompletions()

        // Wire the Phase 6 auto-wake follow-up: when AgentService fires an auto-wake
        // event (e.g. a background process completed and the session is dormant), drive
        // a real session resume through AgentController so all UI callbacks are attached.
        wireAutoWakeListener()

        // Phase 3 Task 3.1 — wire the monitor card listener so monitor events on focused
        // sessions render a visible async-event card in the chat panel (same as background
        // process completions — persisted + live-pushed iff the session is on screen).
        service.setAsyncEventCardListener { sessionId, card -> pushAsyncEventCard(sessionId, card) }

        // Wire document-extraction progress so SessionDocumentArtifactService can push
        // live "page X of Y" updates to the JCEF chat panel while read_document blocks.
        service.onDocumentProgress = ::pushDocumentProgress

        // P2-11: push real tool-window visibility into the webview (document.hidden
        // never flips in embedded JCEF, so webview tickers need this signal).
        subscribeToToolWindowVisibility()
    }

    /**
     * P2-11 — Tool-window visibility signal for the embedded webview.
     *
     * `document.hidden` never flips inside an embedded JCEF browser: the Chromium page
     * stays "visible" even when the Workflow tool window is hidden, so webview 1s
     * tickers (UsageIndicator etc.) that gate on it keep polling forever. The Kotlin
     * side is the only layer that knows the real visibility, so it is pushed in.
     *
     * Contract (the webview does NOT consume this yet — landing the signal is the
     * deliverable; a webview-side consumer can be added later):
     *  - `window.__wfToolWindowVisible: boolean` — current visibility of the "Workflow"
     *    tool window. `undefined` until the first show/hide transition after the chat
     *    panel was created; consumers must treat `undefined` as visible.
     *  - `wf-visibility-change` — `CustomEvent` dispatched on `window` after each
     *    transition, with `detail: { visible: boolean }`.
     *
     * Only the primary (tool-window) browser receives the signal — editor-tab mirrors
     * are independent, always-visible browsers (accepted P2-3, see agent/CLAUDE.md).
     */
    private fun subscribeToToolWindowVisibility() {
        project.messageBus.connect(this).subscribe(
            com.intellij.openapi.wm.ex.ToolWindowManagerListener.TOPIC,
            object : com.intellij.openapi.wm.ex.ToolWindowManagerListener {
                /** stateChanged fires for ANY tool-window change — dedupe on transition. */
                private var lastVisible: Boolean? = null

                override fun stateChanged(toolWindowManager: com.intellij.openapi.wm.ToolWindowManager) {
                    val visible = toolWindowManager
                        .getToolWindow(WORKFLOW_TOOL_WINDOW_ID)?.isVisible ?: return
                    if (visible == lastVisible) return
                    lastVisible = visible
                    pushToolWindowVisibility(visible)
                }
            }
        )
    }

    /** Push one visibility transition into the page (see [subscribeToToolWindowVisibility]). */
    private fun pushToolWindowVisibility(visible: Boolean) {
        dashboard.callJs(
            "window.__wfToolWindowVisible = $visible; " +
                "window.dispatchEvent(new CustomEvent('wf-visibility-change', " +
                "{ detail: { visible: $visible } }));"
        )
    }

    /**
     * Collect [WorkflowEvent.TaskChanged] from [EventBus] and forward each event to the
     * webview as either `_applyTaskCreate(task)` or `_applyTaskUpdate(task)`.
     *
     * Runs on [controllerScope] (cancelled in [dispose]). Best-effort: if the task is
     * missing from the store by the time the event fires, the event is ignored.
     */
    private fun subscribeToTaskChanges() {
        val eventBus = project.getService(EventBus::class.java)
        if (eventBus == null) {
            LOG.warn("[Tasks] subscribeToTaskChanges: EventBus service not available; task UI will not update.")
            return
        }
        controllerScope.launch {
            eventBus.events.collect { event ->
                if (event !is WorkflowEvent.TaskChanged) return@collect
                val store = service.currentTaskStore()
                if (store == null) {
                    LOG.warn("[Tasks] TaskChanged(id=${event.taskId}, isCreate=${event.isCreate}) — no active TaskStore; event dropped.")
                    return@collect
                }
                val task = store.getTask(event.taskId)
                if (task == null) {
                    LOG.warn("[Tasks] TaskChanged(id=${event.taskId}, isCreate=${event.isCreate}) — store.getTask returned null; event dropped (possible race).")
                    return@collect
                }
                val taskJson = taskEventJson.encodeToString(task)
                LOG.info("[Tasks] forwarding to webview — id=${event.taskId} isCreate=${event.isCreate} status=${task.status} subject='${task.subject}'")
                if (event.isCreate) {
                    dashboard.applyTaskCreate(taskJson)
                } else {
                    dashboard.applyTaskUpdate(taskJson)
                }
            }
        }
    }

    /**
     * Shared JSON instance for task bridge payloads. `encodeDefaults = true` ensures
     * default-valued fields (empty `blocks` / `blockedBy`) appear in the JSON so the
     * webview's strict TypeScript types are satisfied.
     */
    private val taskEventJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    /** Lenient JSON instance shared by background-snapshot serialization. */
    private val backgroundJson = Json { ignoreUnknownKeys = true }

    /**
     * Collect [WorkflowEvent.BackgroundChanged] from [EventBus] and forward the snapshot
     * to the webview top-bar indicator via `window.__receiveBackgroundUpdate`.
     *
     * Only events whose [WorkflowEvent.BackgroundChanged.sessionId] matches the current
     * active session are forwarded — events for other sessions are silently ignored.
     *
     * Runs on [controllerScope] (cancelled in [dispose]).
     */
    private fun subscribeToBackgroundChanges() {
        val eventBus = project.getService(EventBus::class.java)
        if (eventBus == null) {
            LOG.warn("[Background] subscribeToBackgroundChanges: EventBus service not available; background indicator will not update.")
            return
        }
        LOG.info("[Background] subscribeToBackgroundChanges: subscribing (currentSessionId=$currentSessionId)")
        controllerScope.launch {
            eventBus.events.collect { event ->
                if (event !is WorkflowEvent.BackgroundChanged) return@collect
                // Capture currentSessionId snapshot once so logging + filter see the same value.
                val active = currentSessionId
                if (active == null) {
                    LOG.info("[Background] dropping BackgroundChanged(session=${event.sessionId}, count=${event.snapshot.size}) — no active session yet")
                    return@collect
                }
                if (event.sessionId != active) {
                    LOG.info("[Background] dropping BackgroundChanged(session=${event.sessionId}) — active session is $active")
                    return@collect
                }
                val json = backgroundJson.encodeToString(
                    ListSerializer(BackgroundProcessSnapshotDto.serializer()),
                    event.snapshot
                )
                LOG.info("[Background] forwarding to webview — session=$active count=${event.snapshot.size} bgIds=${event.snapshot.map { it.bgId }}")
                invokeLater { dashboard.receiveBackgroundUpdate(json) }
            }
        }
    }

    /** Lenient JSON instance shared by monitor-snapshot serialization. */
    private val monitorJson = Json { ignoreUnknownKeys = true }

    /**
     * Collect [WorkflowEvent.MonitorChanged] from [EventBus] and forward the snapshot
     * to the webview top-bar indicator via `window.__receiveMonitorUpdate`.
     *
     * Only events whose [WorkflowEvent.MonitorChanged.sessionId] matches the current
     * active session are forwarded — events for other sessions are silently ignored.
     *
     * Mirrors [subscribeToBackgroundChanges]. Runs on [controllerScope] (cancelled in [dispose]).
     */
    private fun subscribeToMonitorChanges() {
        val eventBus = project.getService(EventBus::class.java)
        if (eventBus == null) {
            LOG.warn("[Monitor] subscribeToMonitorChanges: EventBus service not available; monitor indicator will not update.")
            return
        }
        LOG.info("[Monitor] subscribeToMonitorChanges: subscribing (currentSessionId=$currentSessionId)")
        controllerScope.launch {
            eventBus.events.collect { event ->
                if (event !is WorkflowEvent.MonitorChanged) return@collect
                val active = currentSessionId
                if (active == null) {
                    LOG.info("[Monitor] dropping MonitorChanged(session=${event.sessionId}, count=${event.snapshot.size}) — no active session yet")
                    return@collect
                }
                if (event.sessionId != active) {
                    LOG.info("[Monitor] dropping MonitorChanged(session=${event.sessionId}) — active session is $active")
                    return@collect
                }
                val json = monitorJson.encodeToString(
                    ListSerializer(MonitorSnapshotDto.serializer()),
                    event.snapshot
                )
                LOG.info("[Monitor] forwarding to webview — session=$active count=${event.snapshot.size} ids=${event.snapshot.map { it.id }}")
                invokeLater { dashboard.receiveMonitorUpdate(json) }
            }
        }
    }

    /**
     * Subscribe to [com.workflow.orchestrator.agent.tools.background.BackgroundPool]
     * completion events and surface them as visible status bubbles in the chat.
     *
     * AgentService separately injects these into the loop's steering queue so the LLM
     * sees them at the next iteration boundary — that path makes the completion
     * observable to the model but NOT to the user. This listener closes the UI gap.
     *
     * Only completions for the currently-active session produce a bubble.
     */
    private fun subscribeToBackgroundCompletions() {
        val pool = com.workflow.orchestrator.agent.tools.background.BackgroundPool.getInstance(project)
        Disposer.register(this, pool.addCompletionListener { event ->
            val active = currentSessionId
            if (active == null || event.sessionId != active) {
                LOG.info("[Background] completion for session=${event.sessionId} — not active (active=$active); skipping UI bubble")
                return@addCompletionListener
            }
            val card = com.workflow.orchestrator.agent.ui.AsyncEventCardPresenter.fromBackground(event)
            LOG.info("[Background] pushing completion card — ${card.id} (${card.status})")
            invokeLater { pushAsyncEventCard(active, card) }
        })
    }

    /**
     * Register the auto-wake listener with [AgentService].
     *
     * When a dormant session is auto-woken (e.g. a background process completed, or a
     * cross-IDE delegation result arrived), [AgentService] fires the listener with the
     * target [sessionId] and an optional [syntheticMessage] to inject as the next user turn.
     * We route the call through [resumeSession] so all UI callbacks (streaming, approval
     * gates, stats) are properly wired — identical to a user-initiated resume.
     *
     * BUG #4 — defense-in-depth delivery guard. The waker already declines to fire for a
     * non-active target ([IdleSessionWaker] / DEFER_ACTIVE_SESSION), but its safety check
     * reads the active session id at decision time while the actual resume runs later on the
     * EDT (`invokeLater`). In that window the user could start a NEW chat. So we RE-CHECK here,
     * at the moment of delivery: if a DIFFERENT session is currently running, do NOT hijack it
     * — [resumeSession] → [prepareForReplay] would cancel the live job + reset the chat. The
     * nudge/completion stays persisted (AgentService persist-first) and replays when the user
     * next resumes the target session manually.
     */
    private fun wireAutoWakeListener() {
        service.setAutoWakeListener { sessionId, syntheticMessage ->
            invokeLater {
                if (currentJob?.isActive == true && currentSessionId != null && currentSessionId != sessionId) {
                    LOG.info(
                        "[AgentController] auto-wake for $sessionId skipped — session $currentSessionId is " +
                            "actively running; leaving it persisted for manual resume (no hijack)"
                    )
                    return@invokeLater
                }
                runCatching {
                    val msg = syntheticMessage.ifBlank { null }
                    resumeSession(sessionId, msg)
                }.onFailure {
                    LOG.warn("[AgentController] auto-wake resume failed for session $sessionId: ${it.message}", it)
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════
    //  Callback wiring — dashboard actions → controller
    // ═══════════════════════════════════════════════════

    /**
     * Wire the action / secondary / navigation / mention callbacks shared by the
     * primary dashboard panel and any "View in Editor" mirror panels.
     */
    private fun wireSharedDashboardCallbacks(panel: AgentDashboardPanel) {
        panel.setCefActionCallbacks(
            onCancel = ::cancelTask,
            onNewChat = ::newChat,
            onSendMessage = ::executeTask,
            onChangeModel = ::changeModel,
            onTogglePlanMode = ::togglePlanMode,
            onActivateSkill = { skillName -> executeTask("/skill $skillName") },
            onRequestFocusIde = { /* No-op: focus returns to IDE naturally */ },
            onOpenSettings = ::openSettings,
            onOpenToolsPanel = ::showToolsPanel,
            onRequestModelList = { loadModelList() }
        )
        panel.setCefMentionCallbacks { text, mentionsJson, attachmentsJson ->
            executeTaskWithMentions(text, mentionsJson, attachmentsJson)
        }
        panel.setCefCallbacks(
            onUndo = { LOG.info("Undo requested — not implemented in lean rewrite") },
            onViewTrace = { LOG.info("View trace requested — not implemented in lean rewrite") },
            onPromptSubmitted = ::executeTask
        )
        panel.setCefNavigationCallbacks(onNavigateToFile = ::navigateToFile)
        panel.onValidatePaths = ::handleValidatePaths
        panel.onResolveSymbols = ::handleResolveSymbols
        panel.onSendMessage = ::executeTask
        panel.setOnCompactContext(::compactContext)
        // Phase 5: image-attachment upload path resolves the active session
        // dir on every request (never cached). Returns null when no session
        // is active — AttachmentUploadHandler responds 400 "no_active_session"
        // so the JS toast surfaces a clear error instead of silently writing
        // to a stale session.
        panel.setCurrentSessionDirProvider {
            val basePath = project.basePath ?: return@setCurrentSessionDirProvider null
            // Lazily allocate a session ID + directory the first time something
            // needs them (e.g. an image upload that arrives before the user
            // sends their first message). executeTask passes sessionId =
            // currentSessionId, so AgentService reuses this same ID when the
            // session actually starts — uploads land in the right directory
            // and ImageRefs hydrate from the same place. Synchronized so two
            // concurrent uploads can't allocate two different UUIDs.
            val sid = synchronized(this) {
                currentSessionId ?: java.util.UUID.randomUUID().toString().also {
                    currentSessionId = it
                    LOG.info("AgentController: pre-allocated sessionId=$it for early upload")
                }
            }
            val dir = java.nio.file.Paths.get(
                ProjectIdentifier.agentDir(basePath).absolutePath,
                "sessions",
                sid,
            )
            // Eagerly create the directory; AttachmentStore will create
            // attachments/ inside on first store. Idempotent.
            try {
                java.nio.file.Files.createDirectories(dir)
            } catch (e: Exception) {
                LOG.warn("AgentController: failed to create session dir $dir", e)
                return@setCurrentSessionDirProvider null
            }
            dir
        }
        // Multimodal-agent Phase 7 — chat input usage indicator. Reads (used, max) from the
        // active ContextManager.
        // 3d — DISPLAY key = selected model (keeps the bar identical to the selected picker row).
        // Previously keyed on the running brain model id which diverged from the picker when the
        // user selected a model with a different context window than the one currently executing.
        panel.setContextUsageProvider {
            val cm = contextManager ?: return@setContextUsageProvider null
            val used = cm.currentInputTokens()
            val max = displayMaxInputTokens()
            used to max
        }
        // Phase 7 followup F-P5-2 / F-P6-1 — JS pulls current image settings
        // on page-ready; Kotlin pushes via `pushImageSettings()` after
        // Settings.apply().
        panel.setImageSettingsProvider {
            buildImageSettingsJson()
        }

        // ── File-attachment ingest service (Task 8 / Plan 2026-05-27) ────────
        // Reads bytes off-EDT (controllerScope.launch(Dispatchers.IO)), then
        // delegates to AttachmentIngestService which stores bytes, emits chip
        // metadata to the webview, and activates read_document when needed.
        //
        // FIX 2: create the service ONCE — guard with a null-check so mirror
        // panels (wired via addMirrorPanel → wireSharedDashboardCallbacks) reuse
        // the primary's instance rather than overwriting this.attachmentIngest
        // with a fresh one.  resetTurn() and any other calls on this.attachmentIngest
        // therefore always target the correct (first-created) service.
        val ingest = this.attachmentIngest ?: AttachmentIngestService(
            sessionDirProvider = {
                // Reuse the same lazy-allocate logic as setCurrentSessionDirProvider above:
                // return the current session dir Path, pre-creating it if needed.
                val basePath = project.basePath ?: return@AttachmentIngestService null
                val sid = synchronized(this) {
                    currentSessionId ?: java.util.UUID.randomUUID().toString().also {
                        currentSessionId = it
                        LOG.info("AgentController: pre-allocated sessionId=$it for file attachment")
                    }
                }
                val dir = java.nio.file.Paths.get(
                    ProjectIdentifier.agentDir(basePath).absolutePath,
                    "sessions",
                    sid,
                )
                try {
                    java.nio.file.Files.createDirectories(dir)
                } catch (e: Exception) {
                    LOG.warn("AgentController: failed to create session dir $dir for attachment", e)
                    return@AttachmentIngestService null
                }
                dir
            },
            settingsProvider = {
                val st = com.workflow.orchestrator.core.settings.PluginSettings.getInstance(project).state
                AttachmentIngestService.Settings(
                    imageEnabled = st.enableImageInput,
                    imageMimeWhitelist = st.imageMimeWhitelist.map { it.lowercase() }.toSet(),
                    imageMaxBytes = st.imageMaxBytes,
                    fileMaxBytes = st.fileMaxBytes,
                    imagesPerTurnCap = st.imagesPerTurnCap,
                    filesPerTurnCap = st.filesPerTurnCap,
                )
            },
            onChip = { meta ->
                invokeLater { panel.pushAttachmentChip(chipMetaToJson(meta)) }
            },
            onToast = { msg, kind ->
                invokeLater { dashboard.appendStatus(msg, statusTypeFor(kind)) }
            },
            onFilesAttached = {
                service.activateDeferredTool("read_document")
            },
        ).also { this.attachmentIngest = it }

        // Each panel (primary + mirrors) registers its own pick/drop closures
        // pointing at the SHARED ingest service so file bytes always reach the
        // correct single instance.
        panel.setOnPickAttachment {
            controllerScope.launch(Dispatchers.IO) {
                val st = com.workflow.orchestrator.core.settings.PluginSettings.getInstance(project).state
                val maxBytes = st.fileMaxBytes
                val vFiles = AttachmentPicker(project).choose()
                val (oversized, ok) = vFiles.partition { java.io.File(it.path).length() > maxBytes }
                oversized.forEach { vf ->
                    invokeLater { dashboard.appendStatus("File \"${vf.name}\" is too large (limit ${maxBytes / 1_048_576}MB)", RichStreamingPanel.StatusType.WARNING) }
                }
                if (ok.isNotEmpty()) ingest.ingest(ok.map { readIncoming(java.io.File(it.path)) })
            }
        }
        panel.setOnDropTargetReady { osFiles ->
            controllerScope.launch(Dispatchers.IO) {
                val st = com.workflow.orchestrator.core.settings.PluginSettings.getInstance(project).state
                val maxBytes = st.fileMaxBytes
                val (oversized, ok) = osFiles.partition { it.length() > maxBytes }
                oversized.forEach { f ->
                    invokeLater { dashboard.appendStatus("File \"${f.name}\" is too large (limit ${maxBytes / 1_048_576}MB)", RichStreamingPanel.StatusType.WARNING) }
                }
                if (ok.isNotEmpty()) ingest.ingest(ok.map { readIncoming(it) })
            }
        }
        // ─────────────────────────────────────────────────────────────────────

        // ════════════════════════════════════════════════════════════════
        // Bug 2026-05-21 fix — Mirror panels (e.g. the "View in Editor" chat
        // tab via AgentChatEditor) used to only get the small subset of
        // callbacks above. Plan approve/revise, tool approval, revert,
        // question wizard, history actions, etc. were wired only on the
        // primary dashboard's cefPanel, so the SAME buttons in the mirror
        // silently did nothing. Every JCEF query that originates from the
        // user's clicks must be wired per-panel — JS contexts and bridges
        // are panel-local. State mutations inside each callback body still
        // reference `dashboard.xxx` because the dashboard broadcasts state
        // changes to all mirrors (so primary and mirror UIs stay in sync).
        // ════════════════════════════════════════════════════════════════

        // Retry callback — sends "continue" so the LLM resumes its prior plan rather than
        // restarting from scratch (replaying the original task makes the model think its
        // previous work was wrong and pick a different approach).
        panel.setCefRetryCallback {
            if (lastTaskText == null) return@setCefRetryCallback
            controllerScope.launch(Dispatchers.IO) {
                runCatching { service.cancelCurrentTask() }
                    .onFailure { LOG.warn("retry cancel-prior-task failed (continuing anyway)", it) }
                runCatching { service.cleanEmptyArtifactsBeforeRetry() }
                    .onFailure { LOG.warn("retry cleanup failed (continuing anyway)", it) }
                invokeLater { executeTask("continue", "continue", null) }
            }
        }

        // Plan approval callbacks — full plan lifecycle
        panel.setCefPlanCallbacks(
            onApprove = ::approvePlan,
            onRevise = ::revisePlan
        )
        panel.setCefPlanDismissCallback { dismissPlan() }

        // Handoff card callbacks — new_task propose/fork/decline flow
        panel.setCefHandoffCallbacks(
            onStartFresh = ::startFreshSession,
            onKeepChatting = ::keepChatting
        )

        // "View Plan" button — opens the plan in a full JCEF editor tab
        panel.setCefFocusPlanEditorCallback { openPlanInEditor() }

        // "Open Plan" button on the approved-plan card — reuses the same editor-open logic
        panel.setCefOpenApprovedPlanCallback(::openPlanInEditor)

        // "Revise" button in the chat card — delegates to the open plan editor tab
        panel.setCefRevisePlanFromEditorCallback {
            val editors = FileEditorManager.getInstance(project).allEditors
            val planEditor = editors.filterIsInstance<AgentPlanEditor>().firstOrNull()
            planEditor?.triggerRevise()
        }

        // Tool kill callback
        panel.setCefKillCallback { toolCallId ->
            LOG.info("AgentController: stop requested for tool call $toolCallId")
            ToolStopCoordinator.requestStop(toolCallId)
        }

        // Move-to-background callback
        panel.setCefMoveToBackgroundCallback { toolCallId ->
            LOG.info("AgentController: move-to-background requested for tool call $toolCallId")
            service.moveToolToBackground(toolCallId)
        }

        // Artifact render-result callback — sandbox iframe posts render outcome back
        panel.setCefArtifactResultCallback { json ->
            parseAndDispatchArtifactResult(json)
        }

        // Interactive render round-trip — JS reports whether interactive UI rendered successfully
        panel.setCefInteractiveRenderCallback { json ->
            handleInteractiveRenderResult(json)
        }

        // Approval gate callbacks — user responds to approval cards for write tools
        panel.setCefApprovalCallbacks(
            onApprove = {
                LOG.info("AgentController: tool call approved")
                pendingApproval?.complete(ApprovalResult.APPROVED)
            },
            onDeny = {
                LOG.info("AgentController: tool call denied")
                pendingApproval?.complete(ApprovalResult.DENIED)
            },
            onAllowForSession = { _ ->
                LOG.info("AgentController: tool '${pendingApprovalToolName}' allowed for session")
                pendingApproval?.complete(ApprovalResult.ALLOWED_FOR_SESSION)
            }
        )

        // Steering cancel callback
        panel.setCefCancelSteeringCallback { steeringId ->
            activeSessionQueue()?.remove(steeringId)
            dashboard.removeQueuedSteeringMessage(steeringId)
            LOG.info("AgentController: cancelled steering message $steeringId")
        }

        // Checkpoint v2 — three revert callbacks
        panel.setCefRevertToUserMessageCallback { messageTs ->
            val sid = currentSessionId
            if (sid == null) {
                LOG.warn("AgentController: time-travel requested but no active session")
                return@setCefRevertToUserMessageCallback
            }
            controllerScope.launch(Dispatchers.EDT + CoroutineName("AgentController.revertToUserMessage")) {
                revertToUserMessage(sid, messageTs)
            }
        }

        panel.setCefRevertFileToBaselineCallback { path ->
            val sid = currentSessionId ?: return@setCefRevertFileToBaselineCallback
            controllerScope.launch(Dispatchers.EDT + CoroutineName("AgentController.revertFileToBaseline")) {
                val restored = withContext(Dispatchers.IO) { service.revertFileToBaseline(sid, path) }
                if (restored) pushAggregateDiff(sid)
            }
        }

        panel.setCefRevertAllCallback {
            val sid = currentSessionId ?: return@setCefRevertAllCallback
            controllerScope.launch(Dispatchers.EDT + CoroutineName("AgentController.revertAll")) {
                val earliest = withContext(Dispatchers.IO) { service.firstUserMessageTs(sid) } ?: return@launch
                revertToUserMessage(sid, earliest)
            }
        }

        // Question wizard callbacks — collectedAnswers/skippedQuestionIds are
        // controller-level fields so primary and mirror panels share the same
        // accumulating state (a single wizard interaction at a time).
        panel.setCefQuestionCallbacks(
            onAnswered = { questionId, selectedOptionsJson ->
                collectedAnswers[questionId] = selectedOptionsJson
                skippedQuestionIds.remove(questionId)
            },
            onSkipped = { questionId ->
                collectedAnswers[questionId] = "[]"
                skippedQuestionIds.add(questionId)
            },
            onChatAbout = { _, _, _ -> /* Chat about option not used for tool flow */ },
            onSubmitted = {
                // Wizard answers route through resolveQuestions(), not executeTask(), so the
                // walkthrough paused state set in showQuestionsCallback must be cleared here.
                walkthroughService()?.setGenerationPaused(false)
                dashboard.finalizeQuestionsAsMessage()
                if (pendingApprovalChoice) {
                    handleApprovalChoice(collectedAnswers)
                } else {
                    dashboard.setBusy(true)
                    val snapshot = liveQuestions
                    val enrichedPayload = if (snapshot != null) {
                        buildEnrichedAnswerPayload(snapshot, collectedAnswers, skippedQuestionIds)
                    } else {
                        collectedAnswers.entries.joinToString(",", "{", "}") { (qid, opts) ->
                            "\"$qid\":$opts"
                        }
                    }
                    askQuestionsTool.resolveQuestions(enrichedPayload)
                }
                liveQuestions = null
                collectedAnswers.clear()
                skippedQuestionIds.clear()
            },
            onCancelled = {
                // Wizard cancel routes through cancelQuestions(), not executeTask(), so the
                // walkthrough paused state set in showQuestionsCallback must be cleared here.
                walkthroughService()?.setGenerationPaused(false)
                liveQuestions = null
                if (pendingApprovalChoice) {
                    pendingApprovalChoice = false
                    service.setPlanModeActive(true)
                    dashboard.setPlanMode(true)
                    collectedAnswers.clear()
                    skippedQuestionIds.clear()
                    executeTask("The user is still reviewing the plan. Continue in plan mode.")
                } else {
                    dashboard.setBusy(true)
                    askQuestionsTool.cancelQuestions()
                    collectedAnswers.clear()
                    skippedQuestionIds.clear()
                }
            },
            onEdit = { _ -> /* Re-editing handled by wizard */ }
        )

        // AskUserInputTool's process input callback
        panel.setCefProcessInputCallbacks { input ->
            com.workflow.orchestrator.agent.tools.builtin.AskUserInputTool.resolveInput(input)
        }

        // "Open in editor tab" button on artifact/visualization blocks
        panel.setCefEditorTabCallback { payload ->
            try {
                val root = lenientJson.parseToJsonElement(payload).jsonObject
                val type = root["type"]?.jsonPrimitive?.content ?: "artifact"
                val content = root["content"]?.jsonPrimitive?.content ?: return@setCefEditorTabCallback
                AgentVisualizationEditor.openVisualization(project, type, content)
            } catch (e: Exception) {
                LOG.warn("openInEditorTab: bad payload", e)
            }
        }

        // Tool toggle — user enables/disables a tool via the Tools panel checkbox
        panel.setCefToolToggleCallback { toolName, enabled ->
            LOG.info("AgentController: tool toggle — $toolName enabled=$enabled")
            ToolPreferences.getInstance(project).setToolEnabled(toolName, enabled)
        }

        // Skill dismiss — user clicks the X on the active skill banner
        panel.setCefSkillCallbacks(onDismiss = {
            LOG.info("AgentController: skill dismissed by user")
            contextManager?.clearActiveSkill()
            dashboard.hideSkillBanner()
        })

        // Kill sub-agent
        panel.setCefKillSubAgentCallback { agentId ->
            LOG.info("AgentController: kill sub-agent requested — $agentId")
            val spawnTool = service.registry.get("agent")
                as? com.workflow.orchestrator.agent.tools.builtin.SpawnAgentTool
            val killed = spawnTool?.cancelAgent(agentId) ?: false
            if (killed) {
                LOG.info("AgentController: subagent $agentId cancelled")
            } else {
                LOG.warn("AgentController: subagent $agentId not found in running agents")
            }
        }

        // Busy-case incoming-delegation "Start" click (cross-IDE delegation)
        panel.setCefStartIncomingDelegationCallback { key -> startIncomingDelegation(key) }

        // Session history callbacks
        panel.setCefHistoryCallbacks(
            onShowSession = { sessionId -> showSession(sessionId) },
            onDeleteSession = { sessionId -> handleDeleteSession(sessionId) },
            onToggleFavorite = { sessionId -> handleToggleFavorite(sessionId) },
            onStartNewSession = { handleStartNewSession() },
            onBulkDeleteSessions = { json -> handleBulkDeleteSessions(json) },
            onExportSession = { sessionId -> handleExportSession(sessionId) },
            onExportAllSessions = { handleExportAllSessions() },
            onRequestHistory = { showHistory() },
            onResumeViewedSession = { resumeViewedSession() },
        )

        // Re-push initial state when this panel's page finishes loading. Idempotent.
        panel.setCefPageReadyCallback { pushInitialState() }

        // Background snapshot loader for this panel's webview top-bar indicator
        panel.setCefLoadBackgroundSnapshotCallback { sessionId ->
            val pool = BackgroundPool.getInstance(project)
            val snapshot = pool.list(sessionId).map { h ->
                BackgroundProcessSnapshotDto(
                    bgId = h.bgId,
                    kind = h.kind,
                    label = h.label,
                    state = h.state().name,
                    startedAt = h.startedAt,
                    exitCode = h.exitCode(),
                    outputBytes = h.outputBytes(),
                    runtimeMs = h.runtimeMs(),
                )
            }
            backgroundJson.encodeToString(ListSerializer(BackgroundProcessSnapshotDto.serializer()), snapshot)
        }

        // Monitor snapshot loader for this panel's webview top-bar monitor indicator
        panel.setCefLoadMonitorSnapshotCallback { sessionId ->
            val pool = MonitorPool.getInstance(project)
            val snapshot = pool.list(sessionId).map { h ->
                MonitorSnapshotDto(
                    id = h.bgId,
                    label = h.label,
                    state = h.state().name,
                )
            }
            monitorJson.encodeToString(ListSerializer(MonitorSnapshotDto.serializer()), snapshot)
        }
    }

    /**
     * Multimodal-agent Phase 7 followup F-P5-2 / F-P6-1 — serialize the
     * current [com.workflow.orchestrator.core.settings.PluginSettings] image
     * fields into the JSON shape the React `<InputBar>` expects (mirrors the
     * client-side `IMAGE_DEFAULT_SETTINGS` literal).
     */
    private fun buildImageSettingsJson(): String {
        val state = com.workflow.orchestrator.core.settings.PluginSettings.getInstance(project).state
        val mimes = state.imageMimeWhitelist.joinToString(",", "[", "]") { "\"$it\"" }
        return """{"maxBytes":${state.imageMaxBytes},"mimeWhitelist":$mimes,"maxPerTurn":${state.imagesPerTurnCap},"enabled":${state.enableImageInput}}"""
    }

    // ── File-attachment helpers (Task 8 / Plan 2026-05-27) ───────────────────

    /**
     * Read a java.io.File into an [AttachmentIngestService.IncomingFile].
     * Must be called off-EDT (file I/O + probeContentType).
     */
    private fun readIncoming(f: java.io.File): AttachmentIngestService.IncomingFile {
        val mime = inferMimeType(f)
        return AttachmentIngestService.IncomingFile(f.name, mime, f.readBytes())
    }

    /**
     * Review fix B6: [java.nio.file.Files.probeContentType] returns null on macOS/JBR
     * for common types (PNG, JPEG, PDF, …). A null would route an image as a file.
     * Fall back to extension sniffing so images still reach the vision path.
     */
    private fun inferMimeType(f: java.io.File): String {
        val probed = runCatching { java.nio.file.Files.probeContentType(f.toPath()) }.getOrNull()
        if (!probed.isNullOrBlank()) return probed
        return AttachmentMimeTypes.fromExtension(f.extension)
    }

    /** Serialise [AttachmentIngestService.ChipMeta] to a compact JSON string for the webview. */
    private fun chipMetaToJson(m: AttachmentIngestService.ChipMeta): String {
        val pathJson = m.path?.let { JsEscape.toJsonString(it) } ?: "null"
        return """{"sha256":${JsEscape.toJsonString(m.sha256)},"mime":${JsEscape.toJsonString(m.mime)},""" +
            """"size":${m.size},"originalFilename":${JsEscape.toJsonString(m.originalFilename)},""" +
            """"kind":${JsEscape.toJsonString(m.kind)},"path":$pathJson}"""
    }

    /** Map the ingest-service toast kind ("error" / anything else) to a [RichStreamingPanel.StatusType]. */
    private fun statusTypeFor(kind: String): RichStreamingPanel.StatusType =
        if (kind == "error") RichStreamingPanel.StatusType.WARNING else RichStreamingPanel.StatusType.INFO

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Multimodal-agent Phase 7 followup F-P5-2 / F-P6-1 — entry point for the
     * Settings page to push fresh image settings into the running webview
     * after the user clicks Apply. Public so
     * [com.workflow.orchestrator.core.settings.MultimodalSettingsConfigurable]
     * can call it through the dashboard reference held by `AgentTabProvider`.
     */
    /**
     * Streaming `edit_file` preview — Kotlin → JS push helpers wired into the
     * JCEF bridge as `_streamingEditOpen` / `_streamingEditUpdate` /
     * `_streamingEditFinalize` / `_streamingEditCancel`. Driven by
     * [com.workflow.orchestrator.agent.loop.AgentLoop]'s per-loop
     * `StreamingEditTracker` via a [StreamingEditCallback] supplied at AgentLoop
     * construction in [com.workflow.orchestrator.agent.AgentService].
     *
     * All four are fire-and-forget — JSON-encoded args dispatched onto the JCEF
     * dispatcher (which coalesces calls onto the EDT). Safe to call from any
     * thread; in production they fire from `Dispatchers.IO` inside the SSE
     * chunk handler.
     */
    fun pushStreamingEditOpen(callId: String, path: String, initialDiff: String) {
        val cb = JsEscape.escapeJsonForJsBridge(Json.encodeToString(callId))
        val pa = JsEscape.escapeJsonForJsBridge(Json.encodeToString(path))
        val di = JsEscape.escapeJsonForJsBridge(Json.encodeToString(initialDiff))
        dashboard.callJs("if (window._streamingEditOpen) { window._streamingEditOpen($cb, $pa, $di); }")
    }

    fun pushStreamingEditUpdate(callId: String, diff: String) {
        val cb = JsEscape.escapeJsonForJsBridge(Json.encodeToString(callId))
        val di = JsEscape.escapeJsonForJsBridge(Json.encodeToString(diff))
        dashboard.callJs("if (window._streamingEditUpdate) { window._streamingEditUpdate($cb, $di); }")
    }

    fun pushStreamingEditFinalize(callId: String) {
        val cb = JsEscape.escapeJsonForJsBridge(Json.encodeToString(callId))
        dashboard.callJs("if (window._streamingEditFinalize) { window._streamingEditFinalize($cb); }")
    }

    fun pushStreamingEditCancel(callId: String) {
        val cb = JsEscape.escapeJsonForJsBridge(Json.encodeToString(callId))
        dashboard.callJs("if (window._streamingEditCancel) { window._streamingEditCancel($cb); }")
    }

    /**
     * Concrete [com.workflow.orchestrator.agent.loop.StreamingEditCallback] that
     * fans out to the four push helpers above. Single instance per controller —
     * [com.workflow.orchestrator.agent.AgentService] reads this via
     * [streamingEditCallback] when building each new AgentLoop.
     */
    private val streamingEditCallbackImpl = object : com.workflow.orchestrator.agent.loop.StreamingEditCallback {
        override fun open(callId: String, path: String, initialDiff: String) {
            pushStreamingEditOpen(callId, path, initialDiff)
        }
        override fun update(callId: String, diff: String) {
            pushStreamingEditUpdate(callId, diff)
        }
        override fun finalize(callId: String) {
            pushStreamingEditFinalize(callId)
        }
        override fun cancel(callId: String) {
            pushStreamingEditCancel(callId)
        }
    }

    /**
     * Public accessor so [com.workflow.orchestrator.agent.AgentService] can wire
     * the same instance into every AgentLoop construction for this session/project.
     */
    val streamingEditCallback: com.workflow.orchestrator.agent.loop.StreamingEditCallback
        get() = streamingEditCallbackImpl

    // ── Document-extraction progress streaming ─────────────────────────────────
    // Mirrors the streaming-edit-preview pattern: AgentService's
    // SessionDocumentArtifactService calls `onDocumentProgress` per page;
    // the controller throttles to ≤1 push per 200ms (except "finalizing") and
    // forwards as `window._documentExtractionProgress(json)` / `window._documentExtractionClear()`.
    // Cleared automatically by `onToolCall` when `read_document` completes.

    /** Epoch-ms of the last forwarded progress push; 0 = never pushed. Volatile for IO→any-thread reads. */
    @Volatile private var lastProgressPushMs: Long = 0L

    /**
     * Push a [DocumentExtractionProgress] update to the JCEF webview.
     *
     * Throttled to at most one push every 200ms, but "finalizing" ticks always
     * pass through so the UI can show the closing state promptly. Uses the same
     * `dashboard.callJs(...)` mechanism as the streaming-edit-preview pushes.
     * Safe to call from any thread (IO coroutine inside SessionDocumentArtifactService).
     */
    fun pushDocumentProgress(p: com.workflow.orchestrator.core.model.DocumentExtractionProgress) {
        val now = System.currentTimeMillis()
        if (p.stage != "finalizing" && (now - lastProgressPushMs) < 200L) return
        lastProgressPushMs = now
        val json = buildString {
            append("{\"stage\":")
            append(Json.encodeToString(p.stage))
            append(",\"pagesDone\":")
            append(p.pagesDone)
            append(",\"pagesTotal\":")
            append(if (p.pagesTotal != null) p.pagesTotal.toString() else "null")
            append(",\"elapsedMs\":")
            append(p.elapsedMs)
            append("}")
        }
        val escaped = JsEscape.escapeJsonForJsBridge(Json.encodeToString(json))
        dashboard.callJs("if (window._documentExtractionProgress) { window._documentExtractionProgress($escaped); }")
    }

    /**
     * Clear the extraction progress indicator in the webview.
     * Called when a `read_document` tool call completes (success or error).
     */
    private fun pushDocumentExtractionClear() {
        dashboard.callJs("if (window._documentExtractionClear) { window._documentExtractionClear(); }")
    }
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Push the delegation-question-pending state to IDE-B's webview. Only pushes
     * when the user is viewing the relevant session. Called by
     * [com.workflow.orchestrator.agent.delegation.DelegationInboundService.notifyDelegationQuestionPending].
     *
     * Plan 4 spec §5.5.
     */
    fun pushDelegationQuestionPending(sessionId: String, active: Boolean, delegatorRepo: String?) {
        // Only push if the user is viewing the relevant session.
        if (viewedSessionId != sessionId) return
        controllerScope.launch(Dispatchers.EDT) {
            val payload = buildString {
                append("{\"active\":")
                append(active)
                if (delegatorRepo != null) {
                    append(",\"delegatorRepo\":")
                    append(historyJson.encodeToString(delegatorRepo))
                }
                append("}")
            }
            dashboard.callJs(
                "if (window._setDelegationQuestionPending) " +
                "window._setDelegationQuestionPending(${historyJson.encodeToString(payload)})"
            )
        }
    }

    /**
     * Leg (b): narrate a question routed to the delegator on IDE-B's OWN panel as a
     * delegation card ("↗ Asked {delegatorRepo}", waiting ⏳ until answered). Invoked
     * from [com.workflow.orchestrator.agent.delegation.DelegationInboundService.routeQuestion].
     * Guarded on the delegated session being the active panel session AND null-safe when
     * the controller/webview is absent (tests/headless) — same shape as
     * [pushDelegationQuestionPending]. Also persists the card so a reopened delegated
     * session shows the full conversation.
     */
    fun pushDelegatedQuestionAsked(
        sessionId: String,
        questionId: String,
        delegatorRepo: String?,
        questionText: String,
        options: List<String>,
    ) {
        // Persist regardless of which session is viewed, so the history render is complete.
        service.appendDelegationCardToSession(
            sessionId,
            com.workflow.orchestrator.agent.session.DelegationCardData(
                kind = com.workflow.orchestrator.agent.session.DelegationCardKind.ASKED,
                delegatorRepo = delegatorRepo ?: "the delegator",
                questionId = questionId,
                text = questionText,
                options = options,
                answered = false,
            ),
        )
        if (viewedSessionId != sessionId) return
        controllerScope.launch(Dispatchers.EDT) {
            val payload = buildString {
                append("{\"questionId\":").append(historyJson.encodeToString(questionId))
                append(",\"delegatorRepo\":").append(historyJson.encodeToString(delegatorRepo ?: "the delegator"))
                append(",\"text\":").append(historyJson.encodeToString(questionText))
                append(",\"options\":").append(historyJson.encodeToString(options))
                append("}")
            }
            dashboard.callJs(
                "if (window._appendDelegatedQuestion) " +
                    "window._appendDelegatedQuestion(${historyJson.encodeToString(payload)})"
            )
        }
    }

    /**
     * Leg (c): narrate the answer received from the delegator on IDE-B's OWN panel
     * ("↘ {delegatorRepo} answered"), pairing beneath the matching (b) card and flipping
     * it to resolved. Invoked from
     * [com.workflow.orchestrator.agent.delegation.DelegationInboundService.deliverAnswer]
     * on a winning resolve. Persists the answer card + flips the persisted ASKED card.
     */
    fun pushDelegatedAnswer(
        sessionId: String,
        questionId: String,
        delegatorRepo: String?,
        answerText: String,
    ) {
        service.appendDelegationCardToSession(
            sessionId,
            com.workflow.orchestrator.agent.session.DelegationCardData(
                kind = com.workflow.orchestrator.agent.session.DelegationCardKind.ANSWERED,
                delegatorRepo = delegatorRepo ?: "the delegator",
                questionId = questionId,
                text = answerText,
                answered = true,
            ),
            flipAskedQuestionId = questionId,
        )
        if (viewedSessionId != sessionId) return
        controllerScope.launch(Dispatchers.EDT) {
            val payload = buildString {
                append("{\"questionId\":").append(historyJson.encodeToString(questionId))
                append(",\"delegatorRepo\":").append(historyJson.encodeToString(delegatorRepo ?: "the delegator"))
                append(",\"text\":").append(historyJson.encodeToString(answerText))
                append("}")
            }
            dashboard.callJs(
                "if (window._appendDelegatedAnswer) " +
                    "window._appendDelegatedAnswer(${historyJson.encodeToString(payload)})"
            )
        }
    }

    /**
     * Leg (d): narrate the terminal result sent back to the delegator on IDE-B's OWN panel
     * ("✓ Result sent to {delegatorRepo}", status-colored, with duration + summary). Called
     * from the [onResult] wrapper in [runDelegatedNow] BEFORE the socket [onResult] fires.
     * Persists the result card.
     */
    fun pushDelegatedResult(
        sessionId: String,
        delegatorRepo: String?,
        result: com.workflow.orchestrator.core.delegation.DelegationMessage.Result,
    ) {
        service.appendDelegationCardToSession(
            sessionId,
            com.workflow.orchestrator.agent.session.DelegationCardData(
                kind = com.workflow.orchestrator.agent.session.DelegationCardKind.RESULT,
                delegatorRepo = delegatorRepo ?: "the delegator",
                text = result.summary,
                resultStatus = result.status.name,
                durationSeconds = result.durationSeconds,
                reason = result.reason,
            ),
        )
        if (viewedSessionId != sessionId) return
        controllerScope.launch(Dispatchers.EDT) {
            val payload = buildString {
                append("{\"delegatorRepo\":").append(historyJson.encodeToString(delegatorRepo ?: "the delegator"))
                append(",\"status\":").append(historyJson.encodeToString(result.status.name))
                append(",\"durationSeconds\":").append(result.durationSeconds)
                append(",\"summary\":").append(historyJson.encodeToString(result.summary))
                if (result.reason != null) {
                    append(",\"reason\":").append(historyJson.encodeToString(result.reason!!))
                }
                append("}")
            }
            dashboard.callJs(
                "if (window._appendDelegatedResult) " +
                    "window._appendDelegatedResult(${historyJson.encodeToString(payload)})"
            )
        }
    }

    /** Persist an async-event card and live-push it when the target session is the one on screen. */
    fun pushAsyncEventCard(sessionId: String, card: com.workflow.orchestrator.agent.session.AsyncEventCardData) {
        service.appendAsyncEventCardToSession(sessionId, card)
        if (viewedSessionId != sessionId) return
        controllerScope.launch(Dispatchers.EDT) {
            dashboard.pushAsyncEventCard(historyJson.encodeToString(com.workflow.orchestrator.agent.session.AsyncEventCardData.serializer(), card))
        }
    }

    /**
     * Push the active session's cross-IDE delegation metadata to IDE-B's webview so the
     * `DelegationBanner` (rendered under the top bar) lights up for the LIVE session —
     * "Delegated by {IDE} from {repo}". Without this, the banner only populated when a
     * delegated session was later reopened from history (the webview `HistoryView` path).
     *
     * Pass `null` to clear the banner (new chat / switching to a non-delegated session).
     */
    fun pushActiveSessionDelegated(metadata: com.workflow.orchestrator.agent.session.DelegationMetadata?) {
        controllerScope.launch(Dispatchers.EDT) {
            // Inner payload: the metadata JSON, or the literal `null` token. Then encode
            // that string again so it arrives as a single parseable JS string argument
            // (mirrors pushDelegationQuestionPending's double-encode).
            val payload = if (metadata == null) "null" else historyJson.encodeToString(metadata)
            dashboard.callJs(
                "if (window._setActiveSessionDelegated) " +
                "window._setActiveSessionDelegated(${historyJson.encodeToString(payload)})"
            )
        }
    }

    fun pushImageSettingsToWebview() {
        dashboard.pushImageSettings()
        // Also re-evaluate the view_image registration so the master kill switch
        // takes effect without an IDE restart: flipping `enableImageInput` from
        // OFF→ON in Settings must surface the tool in the LLM's schema for the
        // next turn, and ON→OFF must remove it (defence-in-depth alongside the
        // body guard inside ViewImageTool.execute()).
        try {
            service.reregisterConditionalTools()
        } catch (e: Throwable) {
            LOG.warn("pushImageSettingsToWebview: reregisterConditionalTools failed", e)
        }
    }

    /**
     * Manually compact conversation context. Triggered by the TopBar Compact button.
     *
     * @param force when true, bypasses the 70% utilization floor in [AgentService.compactContext]
     *   so the user can clean up nudge-pair pollution / misbehavior at any utilization level.
     *
     * Safety: blocks while the agent loop is actively running (ContextManager is not thread-safe).
     */
    private fun compactContext(force: Boolean) {
        val cm = contextManager
        if (cm == null) {
            invokeLater { dashboard.appendStatus("No active session to compact.", RichStreamingPanel.StatusType.WARNING) }
            return
        }
        if (currentJob?.isActive == true) {
            invokeLater {
                dashboard.appendStatus(
                    "Cannot compact while agent is running. Wait for the current turn to complete or cancel first.",
                    RichStreamingPanel.StatusType.WARNING
                )
            }
            return
        }
        val utilBefore = cm.utilizationPercent()
        val msgsBefore = cm.messageCount()
        val initialPhase =
            if (force) "Compacting — running full pipeline (dedup + truncate + LLM summary)..."
            else "Compacting context..."
        invokeLater {
            dashboard.setCompactionState(active = true, phase = initialPhase)
        }

        currentJob = controllerScope.launch(Dispatchers.IO) {
            try {
                val result = service.compactContext(cm, force = force)
                if (result == null) {
                    invokeLater {
                        if (force) {
                            // force=true bypasses the shouldCompact() threshold gate, so a null result
                            // means the compaction was Failed (or cancelled by a PreCompact hook).
                            // AgentService already called slidingWindow(0.3) on Failed as a safety net.
                            dashboard.appendStatus(
                                "Compaction failed; conversation history was truncated as a safety net.",
                                RichStreamingPanel.StatusType.WARNING
                            )
                        } else {
                            dashboard.appendStatus(
                                "Context at ${"%.0f".format(utilBefore)}% — below compaction threshold.",
                                RichStreamingPanel.StatusType.INFO
                            )
                        }
                    }
                    return@launch
                }
                val (tokensBefore, tokensAfter) = result
                val utilAfter = cm.utilizationPercent()
                val saved = tokensBefore - tokensAfter
                val msgsAfter = cm.messageCount()
                val ranStage3 = cm.lastCompactionRanSummary
                invokeLater {
                    dashboard.insertCompactionMarker(
                        tokensBefore = tokensBefore,
                        tokensAfter = tokensAfter,
                        messagesBefore = msgsBefore,
                        messagesAfter = msgsAfter,
                        ranLlmSummary = ranStage3,
                    )
                    dashboard.appendStatus(
                        "Compacted: ${"%.0f".format(utilBefore)}% → ${"%.0f".format(utilAfter)}% " +
                            "($tokensBefore → $tokensAfter tokens, saved $saved · " +
                            "$msgsBefore → $msgsAfter messages" +
                            (if (ranStage3) " · LLM summary applied" else "") + ")",
                        RichStreamingPanel.StatusType.INFO
                    )
                }
            } catch (e: Exception) {
                LOG.warn("Manual compaction failed", e)
                invokeLater { dashboard.appendError("Compaction failed: ${e.message}") }
            } finally {
                invokeLater { dashboard.setCompactionState(active = false, phase = "") }
                currentJob = null
            }
        }
    }

    private fun wireCallbacks() {
        // All panel-wirable callbacks (retry, plan, approval, revert, question wizard,
        // history, tool toggles, etc.) are now wired by `wireSharedDashboardCallbacks`
        // so mirror panels (chat-in-editor-tab) receive the same wiring on add. This
        // function only sets up state that is NOT panel-local: tool-level callbacks
        // shared across all panels, the primary's "View in Editor" button, the initial
        // state push, and the JVM-static run_command stream callback.
        wireSharedDashboardCallbacks(dashboard)

        // Wire AskQuestionsTool callbacks
        // Simple mode: show question in chat stream, user types answer via chat input.
        // The tool blocks on pendingQuestions deferred. When the user sends a message,
        // executeTask() intercepts it and resolves the deferred directly.
        askQuestionsTool.showSimpleQuestionCallback = { question, optionsJson ->
            LOG.info("ask_followup_question: callback fired (question=${question.take(80)}, hasOptions=${!optionsJson.isNullOrBlank()})")
            // Drain stream batcher before UI flush so buffered tokens appear before the question
            flushStream()
            invokeLater {
                LOG.info("ask_followup_question: invokeLater running on EDT, dispatching to dashboard")
                try {
                    // Flush any in-progress stream + finalize tool chain so the question
                    // appears AFTER prior tool calls, not mixed in
                    dashboard.flushStreamBuffer()
                    dashboard.finalizeToolChain()

                    // CRITICAL: Clear busy, unlock input, and ensure steering is on.
                    // The agent is waiting for the user's answer, not processing.
                    // The JS-side showQuestions bridge also calls setBusy(false) as a
                    // safety net, but we do it here first so even if showQuestions
                    // never reaches the JS side, the UI is never stuck.
                    LOG.info("ask_followup_question: clearing busy, unlocking input, enabling steering")
                    dashboard.setBusy(false)
                    dashboard.setInputLocked(false)
                    dashboard.setSteeringMode(true)
                    // Walkthrough pause: the wizard suspends on its own deferred and never
                    // reaches onLoopAwaitingUserInput, so pause the tour spinner here (spec §4).
                    walkthroughService()?.setGenerationPaused(true)

                    // Parse options if provided
                    val options = if (!optionsJson.isNullOrBlank()) {
                        try {
                            kotlinx.serialization.json.Json.decodeFromString<List<String>>(optionsJson)
                        } catch (e: Exception) {
                            LOG.warn("ask_followup_question: failed to parse options JSON: ${e.message}, raw='${optionsJson?.take(200)}'")
                            emptyList()
                        }
                    } else emptyList()

                    if (options.isNotEmpty()) {
                        // Questions WITH options → use the QuestionView wizard UI
                        // (clickable radio buttons with descriptions, Skip/Cancel actions)
                        val wizardJson = buildString {
                            append("""{"questions":[{"id":"q1","question":""")
                            append(JsEscape.toJsonString(question))
                            append(""","type":"single","options":[""")
                            options.forEachIndexed { i, opt ->
                                if (i > 0) append(",")
                                append("""{"id":"o${i + 1}","label":""")
                                append(JsEscape.toJsonString(opt))
                                append("}")
                            }
                            append("]}]}")
                        }
                        // Cache question metadata so onSubmitted can resolve option ids → labels
                        liveQuestions = try {
                            val root = lenientJson.parseToJsonElement(wizardJson).jsonObject
                            val questionsArray = root["questions"] ?: throw IllegalStateException("missing questions key")
                            val parsed = lenientJson.decodeFromJsonElement<List<Question>>(questionsArray)
                            LiveQuestions(QuestionMode.SIMPLE, parsed)
                        } catch (e: Exception) {
                            LOG.warn("ask_followup_question: failed to cache question metadata: ${e.message}")
                            null
                        }
                        LOG.info("ask_followup_question: showing wizard with ${options.size} options (wizardJson=${wizardJson.length} chars)")
                        dashboard.showQuestions(wizardJson)
                    } else {
                        // No options — user types freely; no id→label resolution needed, so liveQuestions is not set.
                        // Questions WITHOUT options → user types their answer freely in the chat input.
                        // The question text lives in the tool call params (not in the streamed text),
                        // so we must display it explicitly as an agent message. Use the streaming
                        // pipeline (appendStreamToken + flush) to render it as a normal agent message.
                        LOG.info("ask_followup_question: no options — showing as plain text (question=${question.length} chars)")
                        dashboard.appendStreamToken(question)
                        dashboard.flushStreamBuffer()
                        // The no-options path renders via the streaming-token bridge,
                        // NOT via dashboard.showQuestions. Only showQuestions has a JS
                        // round-trip that calls _reportInteractiveRender (which flips
                        // uiRenderConfirmed). Without this manual confirm, the tool's
                        // 10s watchdog fires [UI_RENDER_FAILED] even though the user
                        // sees the question fine. The streaming-token path doesn't
                        // fail silently, so confirming inline is safe.
                        askQuestionsTool.uiRenderConfirmed = true
                    }
                    dashboard.focusInput()
                } catch (e: Exception) {
                    LOG.warn("ask_followup_question callback failed: ${e.message}", e)
                    // Ensure the UI is never stuck — clear busy and unlock input even on failure.
                    // Also show the question as plain text so the user at least sees it.
                    dashboard.setBusy(false)
                    dashboard.setInputLocked(false)
                    dashboard.setSteeringMode(true)
                    dashboard.appendStreamToken(question)
                    dashboard.flushStreamBuffer()
                    // Same reason as the no-options branch above — the streaming-token
                    // path bypasses showQuestions, so the watchdog never sees the
                    // _reportInteractiveRender confirmation otherwise.
                    askQuestionsTool.uiRenderConfirmed = true
                    dashboard.focusInput()
                }
            }
        }
        // Wizard mode: structured multi-question UI
        askQuestionsTool.showQuestionsCallback = { questionsJson ->
            LOG.info("ask_questions: wizard callback fired (json=${questionsJson.length} chars)")
            // Cache question metadata BEFORE the invokeLater dispatch. This write happens-before
            // AWT's event queue enqueue (JMM §17.4.5), so onSubmitted — which runs on the EDT —
            // is guaranteed to see the updated value. Do NOT move this inside invokeLater, as that
            // would create a window where onSubmitted could fire before the write completes.
            liveQuestions = try {
                val root = lenientJson.parseToJsonElement(questionsJson).jsonObject
                val questionsElement = root["questions"] ?: throw IllegalStateException("missing questions key")
                val parsed = lenientJson.decodeFromJsonElement<List<Question>>(questionsElement)
                LiveQuestions(QuestionMode.WIZARD, parsed)
            } catch (e: Exception) {
                LOG.warn("ask_questions: failed to cache wizard question metadata: ${e.message}")
                null
            }
            invokeLater {
                // Clear busy FIRST — before the showQuestions bridge call which could
                // silently fail on the JCEF/JS side
                LOG.info("ask_questions: clearing busy, unlocking input, enabling steering")
                dashboard.setBusy(false)
                dashboard.setInputLocked(false)
                dashboard.setSteeringMode(true)
                // Walkthrough pause: the wizard suspends on its own deferred and never
                // reaches onLoopAwaitingUserInput, so pause the tour spinner here (spec §4).
                walkthroughService()?.setGenerationPaused(true)
                try {
                    dashboard.showQuestions(questionsJson)
                } catch (e: Exception) {
                    LOG.warn("ask_questions wizard callback failed: ${e.message}", e)
                }
                dashboard.focusInput()
            }
        }
        // AskUserInputTool's tool-side show callback — single global registration,
        // not per-panel. The setCefProcessInputCallbacks JCEF wiring is per-panel
        // and lives in `wireSharedDashboardCallbacks`.
        com.workflow.orchestrator.agent.tools.builtin.AskUserInputTool.showInputCallback = { processId, description, prompt, command ->
            invokeLater { dashboard.showProcessInput(processId, description, prompt, command) }
        }

        // "View in Editor" toolbar button — primary-only by design (already in the
        // editor tab when triggered from a mirror, so wiring it on mirrors would
        // duplicate / re-open). Intentionally NOT in wireSharedDashboardCallbacks.
        dashboard.setOnViewInEditor(::openChatInEditorTab)

        // Push initial state (model, skills, memory) — buffered if page isn't loaded yet.
        // Per-panel page-ready re-push is wired in wireSharedDashboardCallbacks.
        pushInitialState()

        // Route tool process output to per-tool-call Terminal blocks via toolStreamBatcher.
        // TODO: RunCommandTool.streamCallback is a JVM-static field — if two projects are open
        //       simultaneously, the second controller overwrites the first's callback. Migrate to
        //       passing the callback explicitly via AgentService/AgentLoop context to fix multi-project routing.
        RunCommandTool.streamCallback = { toolCallId, chunk -> toolStreamBatcher.append(toolCallId, chunk) }
        LOG.info("AgentController: run_command streamCallback armed")
    }

    // ═══════════════════════════════════════════════════
    //  Question answer enrichment helpers
    // ═══════════════════════════════════════════════════

    /**
     * Builds an enriched answer payload that includes question text and human-readable option
     * labels alongside the selected option ids. This replaces the raw `{"q1":["o1"]}` format
     * that the LLM previously received, which contained no readable context.
     *
     * **Simple mode** (single question routed as a synthetic wizard):
     * Resolves selected ids to their label strings and calls
     * [AskQuestionsTool.resolveQuestions] with the joined label text so `executeSimple`
     * wraps it in `<answer>…</answer>`.
     *
     * **Wizard mode** (structured multi-question wizard):
     * Builds a JSON object per question that includes both the question text and each selected
     * option's id + label. Target format:
     * ```json
     * {"q1":{"question":"Which database?","selected":[{"id":"o1","label":"PostgreSQL"}]}}
     * ```
     *
     * Fallback: if [liveQuestions] is null (unexpected path) the caller falls back to the
     * original raw `joinToString` behaviour — this function is never called in that case.
     */
    private fun buildEnrichedAnswerPayload(
        live: LiveQuestions,
        collectedAnswers: Map<String, String>,
        skippedIds: Set<String> = emptySet()
    ): String = when (live.mode) {
        // SIMPLE: joined labels (or [SKIPPED]); WIZARD: enriched JSON. See AnswerPayloadEnricher.
        QuestionMode.SIMPLE -> AnswerPayloadEnricher.buildSimple(live.questions, collectedAnswers, skippedIds)
        QuestionMode.WIZARD -> AnswerPayloadEnricher.buildWizard(live.questions, collectedAnswers, skippedIds)
    }

    /**
     * Push model name, memory stats, model list, and skills to the webview.
     * Called at init (buffered if page isn't ready) and again from onPageReady
     * (guarantees state arrives even on slow machines).
     */
    private fun pushInitialState() {
        val model = AgentSettings.getInstance(project).state.sourcegraphChatModel
        if (!model.isNullOrBlank()) {
            dashboard.setModelName(model)
        }
        loadModelList()
        loadSkillsList()
    }

    /**
     * Fetch the model list from Sourcegraph and send to the dashboard dropdown.
     * Uses ModelCache (24h TTL) to avoid redundant API calls.
     * Runs in background — failure is non-fatal (dropdown shows current model only).
     */
    /**
     * Fetch models from Sourcegraph, populate the dropdown, and auto-select the best
     * (latest Opus) model. Uses ModelCache (24h TTL) to avoid redundant API calls.
     * Runs in background — failure is non-fatal.
     */
    private fun loadModelList(force: Boolean = false) {
        val connections = com.workflow.orchestrator.core.settings.ConnectionSettings.getInstance()
        if (connections.state.sourcegraphUrl.isBlank()) return

        val credentialStore = com.workflow.orchestrator.core.auth.CredentialStore()
        controllerScope.launch(Dispatchers.IO) {
            val client = com.workflow.orchestrator.core.ai.SourcegraphChatClient(
                baseUrl = connections.state.sourcegraphUrl.trimEnd('/'),
                tokenProvider = { credentialStore.getToken(com.workflow.orchestrator.core.model.ServiceType.SOURCEGRAPH) },
                model = ""
            )
            // Retry on transient failure with exponential backoff (1s, 3s, 9s).
            // Fixes the "No models available" dead-end where a single failed startup
            // fetch (no VPN, blank token, server unreachable) leaves the dropdown empty
            // until IDE restart.
            val backoffsMs = longArrayOf(1_000L, 3_000L, 9_000L)
            var fetched: List<com.workflow.orchestrator.core.ai.dto.ModelInfo>? = null
            for (attempt in 0..backoffsMs.size) {
                val result = try {
                    com.workflow.orchestrator.core.ai.ModelCache.fetchModels(client, force = force && attempt == 0)
                } catch (e: Exception) {
                    LOG.debug("AgentController: model fetch threw: ${e.message}")
                    com.workflow.orchestrator.core.ai.ModelCache.FetchResult.Failed(emptyList(), e.message ?: "exception")
                }
                when (result) {
                    is com.workflow.orchestrator.core.ai.ModelCache.FetchResult.Fresh -> {
                        if (result.models.isNotEmpty()) { fetched = result.models; break }
                    }
                    is com.workflow.orchestrator.core.ai.ModelCache.FetchResult.Cached -> {
                        fetched = result.models; break
                    }
                    is com.workflow.orchestrator.core.ai.ModelCache.FetchResult.Failed -> {
                        if (result.cached.isNotEmpty()) { fetched = result.cached; break }
                    }
                }
                if (attempt < backoffsMs.size) {
                    kotlinx.coroutines.delay(backoffsMs[attempt])
                }
            }
            val models = fetched
            if (models.isNullOrEmpty()) {
                LOG.warn("AgentController: model list still empty after retries — dropdown will be re-fetched on next remount")
                return@launch
            }
            // Fetch catalog early — needed for deprecated filtering and JSON enrichment.
            // Phase 7 — pull capacity / capabilities / status from the live
            // [com.workflow.orchestrator.core.ai.ModelCatalogService] when
            // available. Falls back to the name-heuristic for `vision` and
            // omits the new fields if the catalog hasn't loaded yet — the
            // ModelPickerRow guards against undefined for each.
            val catalog = try {
                project.getService(com.workflow.orchestrator.agent.AgentService::class.java)?.getSharedModelCatalog()
            } catch (_: Throwable) { null }

            // Drop deprecated models — Sourcegraph marks superseded models with
            // status="deprecated" in the catalog; they are hidden from the picker.
            val activeModels = models.filter { m -> catalog?.getStatus(m.id) != "deprecated" }

            // Within each family: thinking variants first, then newest-created first.
            val thinkingFirst =
                compareByDescending<com.workflow.orchestrator.core.ai.dto.ModelInfo> { it.isThinkingModel }
                    .thenByDescending { it.created }

            val opusAll   = activeModels.filter { it.isOpusClass }.sortedWith(thinkingFirst)
            val sonnetAll = activeModels.filter { it.modelName.lowercase().contains("sonnet") }.sortedWith(thinkingFirst)
            val haikuAll  = activeModels.filter { it.modelName.lowercase().contains("haiku") }.sortedWith(thinkingFirst)
            val otherAll  = activeModels
                .filter { !it.isOpusClass && !it.modelName.lowercase().contains("sonnet") && !it.modelName.lowercase().contains("haiku") }
                .sortedWith(compareBy<com.workflow.orchestrator.core.ai.dto.ModelInfo> { it.tier }.thenByDescending { it.created })

            // Compare two models by version number, not by `created` timestamp — see
            // ModelVersionOrdering (fixes the "-latest" alias timestamp-refresh mis-sort).
            val byVersionAsc = Comparator<com.workflow.orchestrator.core.ai.dto.ModelInfo> { a, b ->
                ModelVersionOrdering.compareByVersionAsc(a.modelName, b.modelName)
            }

            // Latest generation = the version key of the numerically highest model in the family.
            val latestOpusKey = opusAll.maxWithOrNull(byVersionAsc)
                ?.let { ModelVersionOrdering.versionKey(it.modelName) }
            val latestSonnetKey = sonnetAll.maxWithOrNull(byVersionAsc)
                ?.let { ModelVersionOrdering.versionKey(it.modelName) }

            val opusLatest   = opusAll.filter   { ModelVersionOrdering.versionKey(it.modelName) == latestOpusKey }
            val opusOlder    = opusAll.filter    { ModelVersionOrdering.versionKey(it.modelName) != latestOpusKey }
            val sonnetLatest = sonnetAll.filter  { ModelVersionOrdering.versionKey(it.modelName) == latestSonnetKey }
            val sonnetOlder  = sonnetAll.filter  { ModelVersionOrdering.versionKey(it.modelName) != latestSonnetKey }

            // Build JSON incrementally so separator objects can be interspersed
            // with model objects in the same flat array.
            fun modelJson(m: com.workflow.orchestrator.core.ai.dto.ModelInfo): String {
                val id = m.id.replace("\"", "\\\"")
                val name = m.displayName.replace("\"", "\\\"")
                val provider = m.provider.replace("\"", "\\\"")
                val thinking = m.isThinkingModel
                val vision = catalog?.supportsVision(m.id) ?: isLikelyVisionCapable(m.id)
                // 3c — selector row: route DISPLAY read through the override-aware resolver keyed
                // on THIS row's model id so the picker capacity strip matches the usage bar.
                val effMax = service.getEffectiveContextWindow().maxInputTokens(m.id)
                val cwReal = catalog?.getContextWindow(m.id, tier = "enterprise")
                val cw = cwReal?.copy(maxInputTokens = effMax)
                    ?: com.workflow.orchestrator.core.ai.dto.ContextWindow(maxInputTokens = effMax, maxOutputTokens = 0, maxUserInputTokens = null)
                val cwField = run {
                    val core = """{"maxInputTokens":${cw.maxInputTokens}"""
                    val withCap = if (cw.maxUserInputTokens != null) {
                        """$core,"maxUserInputTokens":${cw.maxUserInputTokens}}"""
                    } else "$core}"
                    ""","contextWindow":$withCap"""
                }
                val capsField = if (catalog != null) {
                    val parts = mutableListOf<String>()
                    if (catalog.supportsVision(m.id)) parts += "\"vision\""
                    if (catalog.supportsTools(m.id)) parts += "\"tools\""
                    if (m.isThinkingModel) parts += "\"reasoning\""
                    if (parts.isNotEmpty()) ""","capabilities":[${parts.joinToString(",")}]""" else ""
                } else ""
                val statusField = catalog?.getStatus(m.id)?.let { ""","status":"$it"""" } ?: ""
                return """{"id":"$id","name":"$name","provider":"$provider","thinking":$thinking,"vision":$vision$cwField$capsField$statusField}"""
            }

            fun sepJson(label: String): String {
                val escaped = label.replace("\"", "\\\"")
                return """{"id":"__sep_${label.replace(" ", "_")}","name":"$escaped","separator":true}"""
            }

            val jsonItems = mutableListOf<String>()
            // Section 1 & 2: latest opus (thinking first) then latest sonnet (thinking first)
            opusLatest.forEach   { jsonItems += modelJson(it) }
            sonnetLatest.forEach { jsonItems += modelJson(it) }
            // Section 3–5: older opus + sonnet under "Older Models" separator
            if (opusOlder.isNotEmpty() || sonnetOlder.isNotEmpty()) {
                jsonItems += sepJson("Older Models")
                opusOlder.forEach   { jsonItems += modelJson(it) }
                sonnetOlder.forEach { jsonItems += modelJson(it) }
            }
            // Section 6–7: haiku under "Faster Models" separator
            if (haikuAll.isNotEmpty()) {
                jsonItems += sepJson("Faster Models")
                haikuAll.forEach { jsonItems += modelJson(it) }
            }
            // Non-Anthropic / unclassified models appended last
            otherAll.forEach { jsonItems += modelJson(it) }

            val modelsJson = jsonItems.joinToString(",", "[", "]")

            // Auto-select the best model (latest Opus) if no model is configured
            val settings = AgentSettings.getInstance(project)
            val best = com.workflow.orchestrator.core.ai.ModelCache.pickBest(models)
            if (best != null && settings.state.sourcegraphChatModel.isNullOrBlank()) {
                settings.state.sourcegraphChatModel = best.id
                LOG.info("AgentController: auto-selected model: ${best.displayName} (${best.id})")
            }

            com.intellij.openapi.application.invokeLater {
                dashboard.updateModelList(modelsJson)
                // Show the active model's formatted display name
                val activeModel = settings.state.sourcegraphChatModel ?: best?.id ?: ""
                val displayName = models.find { it.id == activeModel }?.displayName ?: activeModel
                if (displayName.isNotBlank()) {
                    dashboard.setModelName(displayName)
                }
            }
            LOG.info("AgentController: loaded ${models.size} models for dropdown")
        }
    }

    /**
     * Push available skills to the dashboard for chat input autocomplete.
     * Skills are loaded from bundled resources + user directories.
     * The React webview uses this list for /skill suggestions and toolbar dropdown.
     */
    private fun loadSkillsList() {
        val basePath = project.basePath ?: return
        try {
            val discovered = com.workflow.orchestrator.agent.prompt.InstructionLoader.discoverSkills(basePath)
            val allSkills = com.workflow.orchestrator.agent.prompt.InstructionLoader.getAvailableSkills(discovered)
            if (allSkills.isNotEmpty()) {
                // Only send user-invocable skills to the UI for slash command / dropdown display.
                // LLM-only skills (systematic-debugging, tdd, etc.) and the auto-injected meta-skill
                // should not appear in the user's skill picker — they're triggered by the LLM.
                val uiSkills = allSkills.filter { it.userInvocable }
                val skillsJson = uiSkills.joinToString(",", "[", "]") { skill ->
                    val name = skill.name.replace("\"", "\\\"")
                    val desc = skill.description.replace("\"", "\\\"").take(200)
                    """{"name":"$name","description":"$desc"}"""
                }
                invokeLater {
                    dashboard.updateSkillsList(skillsJson)
                }
                LOG.info("AgentController: pushed ${uiSkills.size} user-invocable skills to chat UI (${allSkills.size} total)")
            }
        } catch (e: Exception) {
            LOG.debug("AgentController: failed to load skills list: ${e.message}")
        }
    }

    //  Core: executeTask — send user message to agent loop
    // ═══════════════════════════════════════════════════

    /** Display user message in chat UI, with mention chips if available.
     *
     * When [uiMessageOverride] has [UiSay.PLAN_APPROVED], renders the dedicated
     * [appendPlanApprovedMessage] bubble (with icon + "View implementation plan" link)
     * instead of a plain user message, so the live session matches what is restored from disk.
     */
    private fun displayUserMessage(
        text: String,
        mentionsJson: String?,
        uiMessageOverride: com.workflow.orchestrator.agent.session.UiMessage? = null,
        attachments: List<com.workflow.orchestrator.agent.session.ContentBlock.ImageRef> = emptyList(),
        files: List<FileAttachment> = emptyList(),
    ) {
        if (uiMessageOverride?.say == UiSay.PLAN_APPROVED) {
            val markdown = uiMessageOverride.planApprovalData?.planMarkdown.orEmpty()
            dashboard.appendPlanApprovedMessage(markdown)
            return
        }
        // Echo BOTH images (thumbnails) and files (chips) into the bubble so the
        // sent message reflects what the user attached (file chips were missing
        // because files were split out for read-on-demand and never echoed).
        val attachmentsJson = if (attachments.isNotEmpty() || files.isNotEmpty())
            bubbleAttachmentsJson(attachments, files) else null
        if (mentionsJson != null) {
            dashboard.appendUserMessageWithMentions(text, mentionsJson, attachmentsJson)
        } else {
            dashboard.appendUserMessage(text, attachmentsJson)
        }
    }

    /**
     * Execute a user task with resolved mention context.
     * Called from the JCEF bridge when the user sends a message containing @file, @folder,
     * @symbol, @tool, /skill, or #ticket mentions.
     *
     * Resolves mentions into rich context (file contents, ticket details, etc.) on a background
     * thread, then prepends the context to the task and delegates to [executeTask].
     */
    private fun executeTaskWithMentions(text: String, mentionsJson: String, attachmentsJson: String? = null) {
        if (text.isBlank() && mentionsJson == "[]" && attachmentsJson.isNullOrBlank()) return

        // Reset per-turn attachment counters now that the user has pressed Send.
        attachmentIngest?.resetTurn()

        val (attachments, files) = splitAttachmentsJson(attachmentsJson)
        val fileMarker = composeFileMarker(files)

        val mentions = try {
            val arr = Json.parseToJsonElement(mentionsJson).jsonArray
            arr.map { elem ->
                val obj = elem.jsonObject
                MentionContextBuilder.Mention(
                    type = obj["type"]?.jsonPrimitive?.content ?: "",
                    name = obj["label"]?.jsonPrimitive?.content ?: "",
                    value = obj["path"]?.jsonPrimitive?.content ?: ""
                )
            }
        } catch (e: Exception) {
            LOG.warn("AgentController: failed to parse mentions JSON, falling back to plain text", e)
            executeTask(text + fileMarker, displayText = text, attachments = attachments, files = files)
            return
        }

        if (mentions.isEmpty()) {
            executeTask(text + fileMarker, displayText = text, attachments = attachments, files = files)
            return
        }

        // Resolve mention context on IO thread (may hit Jira API, read files),
        // then execute on EDT.
        controllerScope.launch(Dispatchers.IO) {
            val context = try {
                mentionContextBuilder.buildContext(mentions)
            } catch (e: Exception) {
                LOG.warn("AgentController: mention context build failed, sending without context", e)
                null
            }

            val taskWithContext = if (context != null) {
                "$text\n\n<mention_context>\n$context</mention_context>"
            } else {
                text
            }

            invokeLater {
                // Display clean text with chips in UI; pass XML-enriched text to LLM
                executeTask(taskWithContext + fileMarker, displayText = text, displayMentionsJson = mentionsJson, attachments = attachments, files = files)
            }
        }
    }

    /**
     * Execute a user task. Called from the chat input, redirect, or example prompts.
     * Safe to call multiple times for multi-turn conversation.
     *
     * If a loop is currently waiting for user input (plan mode), feeds the message
     * into the existing loop's channel instead of starting a new one. This matches
     * Cline's continuous conversation model where plan mode is a dialogue.
     *
     * @param displayText Clean text for UI display (without XML context). Null = use task.
     * @param displayMentionsJson JSON array of mentions for chip rendering. Null = no chips.
     * @param uiMessageOverride Optional override for the persisted UI message. When provided, the
     *        message history records this message instead of the synthesized USER_MESSAGE. The live
     *        chat bubble still shows [displayText] (or [task] if null). Passed through to
     *        [AgentService.executeTask]. Only meaningful when this call starts a new loop.
     */
    fun executeTask(
        task: String,
        displayText: String? = null,
        displayMentionsJson: String? = null,
        uiMessageOverride: UiMessage? = null,
        attachments: List<com.workflow.orchestrator.agent.session.ContentBlock.ImageRef> = emptyList(),
        files: List<FileAttachment> = emptyList(),
    ) {
        if (task.isBlank() && attachments.isEmpty() && files.isEmpty()) return

        LOG.info("AgentController.executeTask: ${task.take(80)} (attachments=${attachments.size}, files=${files.size})")

        // Phase 4 Prong A: the body was previously executed inline on the JCEF bridge
        // callback thread (EDT), with `runBlocking` around `hookManager.dispatch` and
        // `channel.send`. Both calls froze EDT for the duration of the hook or until
        // the loop consumed the channel message. Launching on `controllerScope` with
        // `Dispatchers.EDT` preserves the "UI mutations run on EDT" invariant while
        // letting the suspend calls park the coroutine instead of the event thread.
        controllerScope.launch(Dispatchers.EDT + CoroutineName("AgentController.executeTask")) {
            executeTaskInternal(task, displayText, displayMentionsJson, uiMessageOverride, attachments, files)
        }
    }

    private suspend fun executeTaskInternal(
        task: String,
        displayText: String?,
        displayMentionsJson: String?,
        uiMessageOverride: UiMessage?,
        attachments: List<com.workflow.orchestrator.agent.session.ContentBlock.ImageRef> = emptyList(),
        files: List<FileAttachment> = emptyList(),
    ) {
        // The text shown in the UI — clean text without mention XML
        val uiText = displayText ?: task

        // Capture-and-clear any armed walkthrough step context at the very top so it is
        // strictly "next message or nothing": if this message hits a short-circuit branch
        // (delegation answer / pending question / parked channel / steering / viewed session)
        // the arm is dropped rather than leaking a stale prefix onto a later fresh turn.
        // It is only APPLIED on the genuine fresh-turn path below.
        val armedRef = armedWalkthroughQuestionRef
        armedWalkthroughQuestionRef = null

        // Gap 15+17: Track last task for retry and session title
        lastTaskText = task
        lastDisplayText = displayText
        lastDisplayMentionsJson = displayMentionsJson

        // USER_PROMPT_SUBMIT hook (ported from Cline's UserPromptSubmit hook)
        // Fires after user input, before processing. Cancellable: can block the message.
        // Cline: "Executes when the user submits a prompt to Cline."
        val hookManager = service.hookManager
        if (hookManager.hasHooks(HookType.USER_PROMPT_SUBMIT)) {
            val hookResult = hookManager.dispatch(
                HookEvent(
                    type = HookType.USER_PROMPT_SUBMIT,
                    data = mapOf(
                        "message" to task
                    )
                )
            )
            if (hookResult is HookResult.Cancel) {
                LOG.info("AgentController: USER_PROMPT_SUBMIT hook cancelled: ${hookResult.reason}")
                return
            }
        }

        // Plan 2 Task 7: IDE-B short-circuit. If this session is delegated and a
        // delegation question is pending, route typed input as a local answer instead
        // of going through the normal user-message / steering path. The CAS in
        // PendingQuestionToken ensures safe competition with Agent-A's potential
        // delegation_answer tool call.
        val inboundService = project.getService(
            com.workflow.orchestrator.agent.delegation.DelegationInboundService::class.java
        )
        val sid = currentSessionId
        val isDelegated = sid != null && service.currentSessionState()?.delegated != null
        if (sid != null && isDelegated && inboundService.hasPendingQuestion(sid)) {
            LOG.info("AgentController: IDE-B short-circuit — routing typed input as delegation local answer for session $sid")
            displayUserMessage(uiText, displayMentionsJson, attachments = attachments, files = files)
            controllerScope.launch(Dispatchers.IO + CoroutineName("AgentController.localAnswer")) {
                val won = inboundService.localAnswer(sid, task)
                LOG.info("AgentController: localAnswer result for session $sid: won=$won")
            }
            return
        }

        // If a simple question is pending (ask_followup_question), resolve it with user's answer
        val pending = askQuestionsTool.pendingQuestions
        if (pending != null && !pending.isCompleted && currentJob?.isActive == true) {
            LOG.info("AgentController: resolving pending question with user answer — setting busy=true, steeringMode=true")
            if (attachments.isNotEmpty()) {
                // Forward image attachments alongside the typed answer. The question
                // completion deferred is text-only by type, so the structured refs
                // ride a sibling field (drained into ToolResult.imageRefs by
                // AskQuestionsTool). The loop then emits ContentBlock.ImageRef blocks
                // and routes the turn to the vision endpoint — same path as a
                // normal image-bearing message. Must be set BEFORE complete() so the
                // awaiting tool coroutine observes it.
                askQuestionsTool.pendingAnswerImageRefs = attachments.map {
                    com.workflow.orchestrator.core.services.ToolResult.ImageRefData(
                        sha256 = it.sha256,
                        mime = it.mime,
                        size = it.size,
                        originalFilename = it.originalFilename,
                    )
                }
                LOG.info("AgentController: ${attachments.size} attachment(s) on pending-question reply forwarded to the LLM via imageRefs")
            }
            displayUserMessage(uiText, displayMentionsJson, attachments = attachments, files = files)
            dashboard.setBusy(true)
            dashboard.setSteeringMode(true)
            walkthroughService()?.setGenerationPaused(false)
            pending.complete(task)
            return
        }

        // If the loop is waiting for user input (plan mode dialogue), feed into it
        val channel = userInputChannel
        if (loopWaitingForInput && channel != null && currentJob?.isActive == true) {
            LOG.info("AgentController: feeding user message into existing loop via channel — setting busy=true, steeringMode=true")
            if (attachments.isNotEmpty()) {
                // channel.send is text-only by type, so stash the structured refs in
                // a single-use side-channel the loop drains when it consumes this
                // message (pendingChannelImageRefs provider). Set BEFORE channel.send
                // so the loop observes it. The images then ride as ContentPart.Image
                // and the turn routes to the vision endpoint, same as a normal message.
                pendingReplyImageRefs.set(attachments)
                LOG.info("AgentController: ${attachments.size} attachment(s) on plan-mode reply forwarded to the LLM via channel image refs")
            }
            displayUserMessage(uiText, displayMentionsJson, uiMessageOverride, attachments, files)
            dashboard.setBusy(true)
            dashboard.setSteeringMode(true)
            // Input is NOT locked — user can always type freely (Cline behavior)
            loopWaitingForInput = false
            walkthroughService()?.setGenerationPaused(false)
            // Stash the typed UI message override so the loop can persist it when it
            // consumes this channel message (e.g. PLAN_APPROVED instead of raw XML).
            if (uiMessageOverride != null) {
                pendingUiMessageOverride.set(uiMessageOverride)
            }
            channel.send(task)
            return
        }

        // If the loop is actively running (not waiting), queue as a steering message.
        // Ported from Claude Code's mid-turn steering: the message is injected into the
        // conversation context at the start of the next loop iteration, so the LLM sees
        // it before its next response. This avoids cancelling the current task.
        if (currentJob?.isActive == true && !loopWaitingForInput) {
            val steeringId = "steer-${System.currentTimeMillis()}-${steeringCounter.incrementAndGet()}"
            LOG.info("AgentController: queuing steering message: ${task.take(80)}")
            activeSessionQueue()?.enqueue(
                QueuedMessage(
                    id = steeringId,
                    kind = QueueSourceKind.USER,
                    body = task,
                    timestamp = System.currentTimeMillis(),
                    priority = UserQueuePolicy.priority,
                ),
            )
            dashboard.addQueuedSteeringMessage(steeringId, uiText)
            return
        }

        // If viewing a previous session, resume it with the user's message
        if (viewedSessionId != null) {
            LOG.info("AgentController: user typed while viewing session — resuming with message")
            resumeViewedSession(task)
            return
        }

        // Cancel any running task before starting a new one
        currentJob?.let { job ->
            if (job.isActive) {
                LOG.info("AgentController: cancelling previous task before starting new one")
                service.cancelCurrentTask()
            }
        }

        // fresh user turn — apply the armed walkthrough step context (captured + cleared at the
        // top) to the MODEL text only; the chat still shows the user's raw words via
        // uiText/displayUserMessage. All delegation/pending/parked/steering/viewed short-circuits
        // returned above, so this is the only place the prefix is applied.
        val modelTask = if (armedRef != null) "[Walkthrough · $armedRef] $task" else task

        // Show user message in the chat UI
        displayUserMessage(uiText, displayMentionsJson, uiMessageOverride, attachments, files)
        LOG.info("executeTask: setting busy=true, steeringMode=true (turn start)")
        dashboard.setBusy(true)
        dashboard.setSteeringMode(true)
        // Input is NOT locked — user can always type (Cline behavior)
        taskStartTime = System.currentTimeMillis()

        // Create context manager on first message, reuse on subsequent turns.
        // v0.83.44 — budget now follows the active model via the Sourcegraph
        // catalog (the legacy `AgentSettings.maxInputTokens` setting was
        // removed). AgentService.newContextManager wires the shared catalog +
        // a `currentModelRef` provider so utilization + compaction recompute
        // instantly on model fallback.
        val isFirstMessage = !sessionActive
        if (isFirstMessage) {
            contextManager = service.newContextManager()
            val attachmentsJson = if (attachments.isNotEmpty() || files.isNotEmpty())
                bubbleAttachmentsJson(attachments, files) else null
            if (displayMentionsJson != null) {
                dashboard.startSessionWithMentions(uiText, displayMentionsJson, attachmentsJson)
            } else {
                dashboard.startSession(uiText, attachmentsJson)
            }
            sessionActive = true
            // "First message wins" — capture the original task once, never overwrite
            if (originalTaskText == null) {
                originalTaskText = task
            }
        }

        // Generate or update conversation title via Haiku (async, non-blocking)
        generateConversationTitle(task, isFirstMessage)

        // Create a fresh input channel for this loop run
        userInputChannel = Channel(Channel.RENDEZVOUS)

        // Launch the agent loop
        val debugEnabled = AgentSettings.getInstance(project).state.showDebugLog
        if (debugEnabled) {
            dashboard.pushDebugLogEntry("session", "task_start", modelTask.take(200), null)
        }
        // Single source of truth: build the full controller→loop UI-callback bundle once
        // (see [buildSessionUiCallbacks] / [SessionUiCallbacks]). The SAME builder feeds the
        // cross-IDE delegated entry points, so a callback added here flows to both paths.
        val ui = buildSessionUiCallbacks()
        currentJob = service.executeTask(
            task = modelTask,
            sessionId = currentSessionId,
            attachments = attachments,
            contextManager = contextManager,
            onStreamChunk = ui.onStreamChunk,
            onToolCall = ui.onToolCall,
            onComplete = ui.onComplete,
            onRetry = ui.onRetry,
            onCompactionState = ui.onCompactionState,
            onModelSwitch = ui.onModelSwitch,
            onPlanResponse = ui.onPlanResponse,
            onPlanPartialContent = ui.onPlanPartialContent,
            onPlanModeToggled = ui.onPlanModeToggled,
            onPlanDiscarded = ui.onPlanDiscarded,
            userInputChannel = userInputChannel,
            pendingChannelImageRefs = { pendingReplyImageRefs.getAndSet(emptyList()) },
            approvalGate = ui.approvalGate,
            sessionApprovalStore = ui.sessionApprovalStore,
            onSubagentProgress = ui.onSubagentProgress,
            onTokenUpdate = ui.onTokenUpdate,
            onSessionStats = ui.onSessionStats,
            onDebugLog = ui.onDebugLog,
            onSessionStarted = ui.onSessionStarted,
            onSteeringDrained = ui.onSteeringDrained,
            onAwaitingUserInput = ui.onAwaitingUserInput,
            uiMessageOverride = uiMessageOverride,
            onUserInputReceived = ui.onUserInputReceived,
            streamingEditCallback = ui.streamingEditCallback,
            onHandoffProposed = ui.onHandoffProposed,
        )

        // Start 30s Haiku phrase timer (if smart working indicator is enabled)
        startPhraseTimer(task)
    }

    /**
     * SINGLE SOURCE OF TRUTH for the controller→loop UI callbacks (see [SessionUiCallbacks]).
     *
     * Every reusable callback that [executeTaskInternal] used to wire inline into `executeTask`
     * lives here. The interactive path ([executeTaskInternal]) and BOTH cross-IDE delegated paths
     * ([runDelegatedNow], [runResumedDelegatedNow]) source their callbacks from this one builder,
     * so a future callback added here automatically flows to the delegated path — that is the
     * structural fix for the "delegated session silently drops callback X" bug class. Pinned by
     * [com.workflow.orchestrator.agent.delegation.SessionUiCallbacksParityTest].
     *
     * Delegated callers `.copy()` the bundle to override [SessionUiCallbacks.onSessionStarted]
     * (banner + viewedSessionId wiring) and [SessionUiCallbacks.onComplete] (delegated spinner
     * finalize without the generic completion card — the delegation result card is the terminal
     * card; see #1 reconciliation in [runDelegatedNow]).
     */
    fun buildSessionUiCallbacks(): SessionUiCallbacks {
        val debugEnabled = AgentSettings.getInstance(project).state.showDebugLog
        return SessionUiCallbacks(
            onStreamChunk = ::onStreamChunk,
            onToolCall = ::onToolCall,
            onComplete = { result ->
                if (AgentSettings.getInstance(project).state.showDebugLog) {
                    val status = when (result) {
                        is LoopResult.Completed -> "completed"
                        is LoopResult.Cancelled -> "cancelled"
                        is LoopResult.Failed -> "failed"
                        is LoopResult.SessionHandoff -> "handoff"
                    }
                    dashboard.pushDebugLogEntry("session", "task_end", status, null)
                }
                onComplete(result)
            },
            onRetry = { attempt, maxAttempts, reason, delayMs ->
                invokeLater {
                    val delaySec = delayMs / 1000
                    dashboard.appendStatus(
                        "$reason — retrying ($attempt/$maxAttempts) in ${delaySec}s...",
                        RichStreamingPanel.StatusType.WARNING
                    )
                }
            },
            onCompactionState = { active, phase ->
                // Bug 5 — surface auto-compaction to the webview so input locks +
                // overlay shows during the LLM-summary round-trip.
                invokeLater {
                    dashboard.setCompactionState(active, phase)
                    // When compaction finishes the context shrank — refresh the usage bar at once
                    // instead of waiting for its 1s poll (which may be paused via document.hidden).
                    if (!active) dashboard.refreshContextUsage()
                }
            },
            onModelSwitch = { _, to, reason ->
                invokeLater {
                    val cached = com.workflow.orchestrator.core.ai.ModelCache.getCached()
                    // Opportunistic dropdown recovery: a fallback firing means a successful API
                    // call also just landed on the new model, so the network is reachable.
                    // If the dropdown is still empty (initial fetch failed at startup), retry now.
                    if (cached.isEmpty()) loadModelList(force = true)
                    val displayName = cached.find { it.id == to }?.displayName
                        ?: com.workflow.orchestrator.core.ai.dto.ModelInfo.formatModelName(to.substringAfterLast("::"))
                    dashboard.setModelName(displayName)
                    // Subtle in-chip indicator instead of a noisy chat status line.
                    // - "Network error — falling back": fallback ON, tooltip explains why
                    // - "Escalating back": optimistically clear (silent recovery — Option X)
                    // - "Escalation failed — reverting": fallback ON again with the failure reason
                    when {
                        reason.startsWith("Escalating back", ignoreCase = true) ->
                            dashboard.setModelFallbackState(false, null)
                        else ->
                            dashboard.setModelFallbackState(true, "$reason — now using $displayName")
                    }
                }
            },
            onPlanResponse = { text, explore, append -> onPlanResponse(text, explore, append) },
            onPlanPartialContent = ::onPlanPartialContent,
            onPlanModeToggled = { enabled -> invokeLater { togglePlanMode(enabled) } },
            onPlanDiscarded = { invokeLater { onPlanDiscardedByLlm() } },
            approvalGate = ::approvalGate,
            sessionApprovalStore = sessionApprovalStore,
            onSubagentProgress = ::onSubagentProgress,
            onTokenUpdate = ::onTokenUpdate,
            onSessionStats = ::onSessionStats,
            onDebugLog = if (debugEnabled) { level, event, detail, meta ->
                dashboard.pushDebugLogEntry(level, event, detail, meta)
            } else null,
            onSessionStarted = { sid -> currentSessionId = sid },
            onSteeringDrained = { drainedIds ->
                invokeLater { handleSteeringDrained(drainedIds) }
            },
            onAwaitingUserInput = ::onLoopAwaitingUserInput,
            onUserInputReceived = { _ ->
                // Consume and clear the pending override atomically.
                // The override was set in the loopWaitingForInput branch by handleApprovalChoice
                // (and similar callers) so that the persisted bubble shows the typed message
                // (e.g. PLAN_APPROVED) rather than the raw XML instruction text.
                pendingUiMessageOverride.getAndSet(null)
            },
            streamingEditCallback = streamingEditCallback,
            onHandoffProposed = ::onHandoffProposed,
        )
    }

    // ═══════════════════════════════════════════════════
    //  Streaming callbacks — agent loop → dashboard
    // ═══════════════════════════════════════════════════

    private fun onStreamChunk(chunk: String) {
        // Capture a rolling snippet of the LLM's output for Haiku phrase context
        lastStreamSnippet = (lastStreamSnippet + chunk).takeLast(150)
        dispatchSplitParts(thinkingSplitter.consume(chunk))
    }

    /**
     * Routes parts from [thinkingSplitter] to their respective bridges.
     * `Text` parts continue through the regular 16ms-batched stream pipe;
     * `ThinkingDelta` parts stream live through `appendToThinking`, which the
     * webview renders via `<ThinkingView isStreaming={true}>` (prompt-kit
     * `Reasoning` + `TextShimmer`); `ThinkingEnd` finalizes the block so the
     * webview moves it from the streaming slot into the persistent message
     * list (where it auto-collapses).
     */
    private fun dispatchSplitParts(parts: List<ThinkingTagSplitter.Part>) {
        for (part in parts) when (part) {
            is ThinkingTagSplitter.Part.Text -> streamBatcher.append(part.text)
            is ThinkingTagSplitter.Part.ThinkingDelta -> {
                if (thinkingBlockStartedAt == 0L) thinkingBlockStartedAt = System.currentTimeMillis()
                // P1-12: coalesce instead of one bridge call per SSE chunk.
                thinkingStreamBatcher.append(part.text)
            }
            ThinkingTagSplitter.Part.ThinkingEnd -> {
                val durationMs = if (thinkingBlockStartedAt > 0L) System.currentTimeMillis() - thinkingBlockStartedAt else 0L
                thinkingBlockStartedAt = 0L
                // Ordering: ONE EDT runnable drains the batcher AND closes the block.
                // flush() delivers inline here (EDT-inline invoker), so the tail delta
                // lands before endThinking within the same EDT event — EDT serialization
                // closes the drained-but-undelivered window a flush-then-post pair left
                // open (an EDT timer tick could drain without having delivered yet).
                invokeLater {
                    thinkingStreamBatcher.flush()
                    dashboard.endThinking(durationMs)
                }
            }
        }
    }

    /**
     * Drain both the thinking splitter and the stream batcher. Splitter goes
     * first so any held-back text remainder lands in the batcher before its
     * own flush — otherwise the tail tokens would be lost.
     */
    private fun flushStream() {
        dispatchSplitParts(thinkingSplitter.flush())
        thinkingStreamBatcher.flush()
        streamBatcher.flush()
    }

    /** Reset the pre-bridge buffers — used on cancel / new task. */
    private fun clearStream() {
        thinkingSplitter.reset()
        thinkingStreamBatcher.clear()
        streamBatcher.clear()
        // Sub-agent thinking buffers die with the session too — a cancelled run's tail
        // deltas must not deliver into the next chat (W4-B3 review minor #2).
        subAgentThinkingBatcher.clear()
    }

    /**
     * Approval gate -- suspends the agent loop until the user approves, denies,
     * or allows a write tool for the session.
     *
     * Ported from Cline's approval flow: Cline shows an approval card in the webview
     * and waits for the user's response before proceeding. We use a CompletableDeferred
     * to suspend the coroutine without blocking a thread.
     *
     * @param toolName the tool requesting approval (e.g. "edit_file", "run_command")
     * @param args the raw JSON arguments string
     * @param riskLevel "low", "medium", or "high" risk classification
     * @param allowSessionApproval whether the UI should offer "allow for session" (false for run_command)
     * @return the user's decision
     */
    private suspend fun approvalGate(toolName: String, args: String, riskLevel: String, allowSessionApproval: Boolean): ApprovalResult {
        // Read the sub-agent origin context — present when the approval bubbles up from a
        // sub-agent run wrapped by withSubagentOrigin(). Null for orchestrator-level approvals.
        val origin = kotlin.coroutines.coroutineContext[
            com.workflow.orchestrator.agent.tools.subagent.SubagentOriginContext.Key
        ]
        val originAgentId = origin?.agentId
        val originLabel = origin?.label

        // Hoisted ABOVE the pendingApproval reentry guard so the edit_file preview short-circuit
        // can return APPROVED without installing a deferred that would leak through `finally`.
        val parsedArgs = try {
            kotlinx.serialization.json.Json.parseToJsonElement(args).jsonObject
        } catch (_: Exception) { null }

        val approvalPath = parsedArgs?.get("path")?.jsonPrimitive?.content
            ?: parsedArgs?.get("file_path")?.jsonPrimitive?.content

        // Upstream edit_file preview: pre-validate (path/file/match/ambiguity) and compute the
        // real file-anchored diff. ValidationFailed → return APPROVED immediately so execute()
        // runs, fails with the precise error, and surfaces it to the LLM — without bothering
        // the user with a false-positive approval card. Defense-in-depth: execute() revalidates
        // everything.
        var editPreviewReady: com.workflow.orchestrator.agent.tools.builtin.EditFileTool.EditPreview.Ready? = null
        if (toolName == "edit_file" && parsedArgs != null) {
            val previewResult = runCatching {
                com.workflow.orchestrator.agent.tools.builtin.EditFileTool.preview(parsedArgs, project)
            }.getOrNull()
            when (previewResult) {
                is com.workflow.orchestrator.agent.tools.builtin.EditFileTool.EditPreview.ValidationFailed -> {
                    LOG.debug(
                        "AgentController: edit_file preview failed for $toolName — " +
                            "skipping approval card, execute() will surface the error"
                    )
                    return ApprovalResult.APPROVED
                }
                is com.workflow.orchestrator.agent.tools.builtin.EditFileTool.EditPreview.Ready -> {
                    editPreviewReady = previewResult
                }
                null -> {
                    // Unexpected exception escaped runCatching — fall back to the naive snippet diff
                    // path so the user still sees something rather than a misleading early approve.
                }
            }
        }

        val deferred = CompletableDeferred<ApprovalResult>()
        // Defensive reentry guard — see the invariant described on [pendingApproval].
        // If a second approvalGate call arrives while the first is still waiting,
        // cancel the previous deferred so the old await() throws instead of hanging
        // forever (a race we would otherwise have no way to detect). This should
        // never fire under the current architecture; a warning here is the signal
        // that someone plumbed the approval gate into a parallel worker.
        val stale = pendingApproval
        if (stale != null && !stale.isCompleted) {
            LOG.warn(
                "AgentController: approvalGate re-entered while a prior approval " +
                    "(tool='${pendingApprovalToolName}') was still pending — " +
                    "cancelling the stale deferred. New tool='$toolName'. " +
                    "This indicates a concurrency bug — see pendingApproval docs."
            )
            stale.cancel(CancellationException("approvalGate re-entered"))
        }
        pendingApproval = deferred
        pendingApprovalToolName = toolName

        val description: String
        val diffContent: String?
        var commandPreviewJson: String? = null

        when (toolName) {
            "edit_file" -> {
                val path = parsedArgs?.get("path")?.jsonPrimitive?.content ?: "unknown"
                val oldString = parsedArgs?.get("old_string")?.jsonPrimitive?.content ?: ""
                val newString = parsedArgs?.get("new_string")?.jsonPrimitive?.content ?: ""
                val editDesc = parsedArgs?.get("description")?.jsonPrimitive?.content
                description = editDesc ?: "Edit $path"
                // Prefer the real file-anchored diff (so @@ hunk headers carry real line offsets
                // and diff2html renders correct line numbers). Fall back to the naive snippet
                // diff only when preview() couldn't compute one (e.g., transient exception).
                diffContent = editPreviewReady?.realDiff
                    ?: com.workflow.orchestrator.agent.util.DiffUtil.unifiedDiff(oldString, newString, path)
            }
            "create_file" -> {
                val path = parsedArgs?.get("path")?.jsonPrimitive?.content ?: "unknown"
                val content = parsedArgs?.get("content")?.jsonPrimitive?.content ?: ""
                description = "Create $path"
                val preview = if (content.length > 2000) content.take(2000) + "\n... (${content.length} chars total)" else content
                diffContent = com.workflow.orchestrator.agent.util.DiffUtil.unifiedDiff("", preview, path)
            }
            "run_command" -> {
                val payload = com.workflow.orchestrator.agent.ui.approval.CommandApprovalPayload.build(parsedArgs, project)
                description = payload.description
                diffContent = null
                commandPreviewJson = payload.commandPreviewJson
            }
            "revert_file" -> {
                val path = parsedArgs?.get("path")?.jsonPrimitive?.content ?: "unknown"
                description = "Revert $path to last saved state"
                diffContent = null
            }
            else -> {
                description = "Execute $toolName"
                diffContent = args.take(500)
            }
        }

        val metadataJson = """{"tool":"$toolName","riskLevel":"$riskLevel"}"""

        // Show the approval card in the dashboard (on EDT)
        invokeLater {
            dashboard.showApproval(
                toolName = toolName,
                riskLevel = riskLevel,
                description = description,
                metadataJson = metadataJson,
                diffContent = diffContent,
                commandPreviewJson = commandPreviewJson,
                allowSessionApproval = allowSessionApproval,
                originAgentId = originAgentId,
                originLabel = originLabel,
                path = approvalPath,
            )
        }

        // Suspend until the user responds (approve/deny/allow for session)
        return try {
            val result = deferred.await()
            // Bug 2: flush any tokens that streamed before the approval gate
            // suspended the loop, so post-approval tokens start a fresh bubble
            // rather than concatenating onto the pre-gate text. Only the
            // "proceed" outcomes resume tool execution and subsequent streaming;
            // DENIED reports the denial and finalizes naturally.
            if (result == ApprovalResult.APPROVED || result == ApprovalResult.ALLOWED_FOR_SESSION) {
                invokeLater { handleApprovalResumeFlush() }
            }
            result
        } finally {
            // Only clear the slot if we still own it — if a reentrant caller
            // replaced our deferred while we were suspended, leave its entry alone.
            if (pendingApproval === deferred) {
                pendingApproval = null
                pendingApprovalToolName = null
            }
        }
    }

    /**
     * True when this update carries ONLY a thinking delta (plus the stats snapshot every
     * update carries) — the P1-12 fast-path predicate for [onSubagentProgress]. Stats on
     * these updates only change at API-call and tool boundaries, which emit their own
     * dedicated updates, so the fast path may skip the per-chunk iteration repaint.
     */
    private fun isThinkingOnlyDelta(update: SubagentProgressUpdate): Boolean {
        if (update.thinkingDelta == null || update.thinkingEnd) return false
        if (update.status != null || update.streamDelta != null) return false
        return update.toolStartName == null && update.toolCompleteName == null && !update.statusNoteSet
    }

    /**
     * Sub-agent progress callback — streams sub-agent lifecycle events to the dashboard.
     * Called by SpawnAgentTool via AgentService when sub-agents report status changes.
     * Not a suspend function — wraps UI updates in invokeLater.
     */
    private fun onSubagentProgress(agentId: String, update: SubagentProgressUpdate) {
        // P1-12 fast path: thinking-only deltas arrive once per SSE chunk (thousands per
        // response on thinking models, × up to 5 parallel sub-agents). Append to the
        // agentId-keyed 16ms batcher and return — no per-chunk invokeLater, no per-chunk
        // iteration repaint.
        val thinkingDelta = update.thinkingDelta
        if (thinkingDelta != null && isThinkingOnlyDelta(update)) {
            subAgentThinkingBatcher.append(agentId, thinkingDelta)
            return
        }
        invokeLater {
            when (update.status) {
                SubagentExecutionStatus.RUNNING -> {
                    // SpawnAgentTool emits RUNNING exactly once per child, with the
                    // human-readable label and chosen model set on the same update.
                    // The webview dedupes on agentId, so this call materialises one
                    // card per real run and shows which model the worker is using.
                    val label = update.label ?: update.latestToolCall ?: "Starting..."
                    val displayModel = update.model?.let { id ->
                        val modelName = id.substringAfterLast("::", id)
                        com.workflow.orchestrator.core.ai.dto.ModelInfo.formatModelName(modelName)
                    }
                    dashboard.spawnSubAgent(agentId, label, displayModel)
                }
                SubagentExecutionStatus.COMPLETED -> {
                    // P1-12 ordering: deliver any tail batched thinking BEFORE the
                    // completion card (EDT-inline invoker → synchronous delivery here).
                    subAgentThinkingBatcher.flush(agentId)
                    update.stats?.let { s ->
                        subagentAccumIn += s.inputTokens.toLong()
                        subagentAccumOut += s.outputTokens.toLong()
                        lastParentStats?.let { p ->
                            dashboard.updateSessionStats(p.modelId, p.tokensIn + subagentAccumIn, p.tokensOut + subagentAccumOut, p.costUsd)
                        }
                    }
                    dashboard.completeSubAgent(
                        agentId,
                        update.result ?: "Completed",
                        update.stats?.inputTokens?.plus(update.stats.outputTokens) ?: 0,
                        isError = false
                    )
                }
                SubagentExecutionStatus.FAILED -> {
                    // P1-12 ordering: tail batched thinking before the failure card.
                    subAgentThinkingBatcher.flush(agentId)
                    update.stats?.let { s ->
                        subagentAccumIn += s.inputTokens.toLong()
                        subagentAccumOut += s.outputTokens.toLong()
                        lastParentStats?.let { p ->
                            dashboard.updateSessionStats(p.modelId, p.tokensIn + subagentAccumIn, p.tokensOut + subagentAccumOut, p.costUsd)
                        }
                    }
                    dashboard.completeSubAgent(
                        agentId,
                        update.error ?: "Failed",
                        update.stats?.inputTokens?.plus(update.stats.outputTokens) ?: 0,
                        isError = true
                    )
                }
                SubagentExecutionStatus.PENDING, null -> {
                    // Transient sub-agent status (retry / compaction) — routed to the SUB-AGENT
                    // CARD, never the orchestrator's main chat. statusNoteSet marks it authoritative
                    // (null clears). This is the fix for subagent retry/compaction leaking to main chat.
                    if (update.statusNoteSet) {
                        dashboard.setSubAgentStatusNote(agentId, update.statusNote)
                    }
                    // Tool starting — add a RUNNING tool chip to the subagent's chain.
                    // The toolCallId from [ToolCallProgress] is threaded through so the
                    // webview can key parallel tool calls by exact ID instead of relying
                    // on a first-RUNNING-by-name lookup (which would swap results for
                    // parallel calls to the same tool — e.g. concurrent read_files).
                    update.toolStartName?.let { name ->
                        dashboard.addSubAgentToolCall(
                            agentId,
                            update.toolCallId,
                            name,
                            update.toolStartArgs ?: ""
                        )
                    }
                    // Tool completing — flip the matching RUNNING chip to COMPLETED/ERROR.
                    update.toolCompleteName?.let { name ->
                        dashboard.updateSubAgentToolCall(
                            agentId,
                            update.toolCallId,
                            name,
                            update.toolCompleteResult ?: "",
                            update.toolCompleteOutput,
                            update.toolCompleteDiff,
                            update.toolCompleteDurationMs,
                            update.toolCompleteIsError,
                            // Multimodal-agent Phase 6 — pass tool-produced
                            // image metadata to the sub-agent UI for badge.
                            update.toolCompleteImageRefs
                        )
                    }
                    // Stream delta — raw LLM token to append to the sub-agent card's last
                    // partial TEXT message. Kept separate from tool chip events so streaming
                    // is fast-pathed without re-serialising stats.
                    update.streamDelta?.let { delta ->
                        dashboard.appendSubAgentStreamDelta(agentId, delta)
                    }
                    // Thinking delta — incremental <thinking> block byte for the sub-agent
                    // card's collapsible REASONING bubble. Thinking-ONLY updates take the
                    // fast path above; a delta riding on a mixed update still goes through
                    // the same keyed batcher (P1-12) so bytes never reorder across paths.
                    update.thinkingDelta?.let { delta ->
                        subAgentThinkingBatcher.append(agentId, delta)
                    }
                    if (update.thinkingEnd) {
                        // P1-12 ordering: tail bytes must land inside the block — the
                        // EDT-inline invoker delivers synchronously before the close.
                        subAgentThinkingBatcher.flush(agentId)
                        dashboard.endSubAgentThinking(agentId)
                    }
                    update.stats?.let { stats ->
                        dashboard.updateSubAgentIteration(agentId, stats.toolCalls, stats.inputTokens + stats.outputTokens)
                    }
                }
            }
        }
    }

    /**
     * Token usage callback — agent loop reports per-call token counts after each API call.
     * The first argument is the current prompt token count (how full the context window is),
     * NOT a cumulative total. The progress bar shows "promptTokens / maxInputTokens".
     */
    private fun onTokenUpdate(promptTokens: Int, completionTokens: Int) {
        invokeLater {
            // v0.83.44 — budget follows the live per-model number from the
            // Sourcegraph catalog (e.g. Sonnet → 132K, Sonnet-thinking → 93K),
            // not the static `AgentSettings.maxInputTokens` setting (removed).
            // DISPLAY key = selected model so TopBar matches the picker row.
            val maxTokens = displayMaxInputTokens()
            dashboard.updateProgress("", promptTokens, maxTokens)
        }
    }

    private fun onSessionStats(modelId: String, tokensIn: Long, tokensOut: Long, costUsd: Double?) {
        lastParentStats = SessionStatsSnapshot(modelId, tokensIn, tokensOut, costUsd)
        invokeLater {
            dashboard.updateSessionStats(modelId, tokensIn + subagentAccumIn, tokensOut + subagentAccumOut, costUsd)
        }
    }

    /** Tool names that render through dedicated UI paths, not generic tool cards. */
    private val COMMUNICATION_TOOLS = setOf(
        "plan_mode_respond",   // Rendered by onPlanResponse callback → PlanCard
        "ask_followup_question", // Rendered by showSimpleQuestionCallback → text or QuestionView
        "ask_questions",       // Rendered by showQuestionsCallback → QuestionView wizard
        "attempt_completion",  // Rendered by onComplete callback → CompletionCard
    )

    /**
     * Tools whose successful completion changes the file tree and should trigger
     * an aggregate-diff refresh. Subset of AgentLoop.WRITE_TOOLS — excludes
     * `run_command`, `background_process`, `send_stdin` since those don't carry
     * path args and aren't snapshotted by the checkpoint store.
     */
    private val CHECKPOINT_RELEVANT_TOOLS = setOf(
        "edit_file", "create_file", "revert_file",
        "format_code", "optimize_imports", "refactor_rename"
    )

    private fun onToolCall(progress: ToolCallProgress) {
        // ── Communication tools: render as text, not tool cards ──
        // These tools have dedicated UI rendering paths (callbacks, cards, wizards).
        // Showing them as generic tool call cards would duplicate or conflict with their real UI.

        // Skip entirely: these are fully handled by other callbacks
        if (progress.toolName in setOf("plan_mode_respond", "ask_followup_question", "ask_questions", "attempt_completion")) return

        // Track recent tool calls for Haiku phrase generator — extract the most useful arg
        if (progress.result.isEmpty() && progress.durationMs == 0L) {
            val contextHint = try {
                val obj = kotlinx.serialization.json.Json.parseToJsonElement(progress.args).jsonObject
                // Try common arg names in priority order for a meaningful context hint
                (obj["path"]?.jsonPrimitive?.content?.substringAfterLast("/")
                    ?: obj["action"]?.jsonPrimitive?.content
                    ?: obj["query"]?.jsonPrimitive?.content?.take(40)
                    ?: obj["command"]?.jsonPrimitive?.content?.take(40)
                    ?: obj["project_key"]?.jsonPrimitive?.content
                    ?: obj["pattern"]?.jsonPrimitive?.content?.take(40)
                    ?: obj["issue_key"]?.jsonPrimitive?.content
                    ?: obj["branch"]?.jsonPrimitive?.content
                    ?: "")
            } catch (_: Exception) { "" }
            synchronized(recentToolCalls) {
                recentToolCalls.add(progress.toolName to contextHint)
                if (recentToolCalls.size > 3) recentToolCalls.removeAt(0)
            }
        }

        // Flush any in-flight tool output chunks BEFORE the EDT dispatch, so terminal output
        // arrives before the result card is committed. flush() is thread-safe.
        if (progress.result.isNotEmpty() || progress.durationMs != 0L) {
            toolStreamBatcher.flush(progress.toolCallId)
        }
        invokeLater {
            if (progress.result.isEmpty() && progress.durationMs == 0L) {
                // Resolve the displayable per-call timeout for tools whose UI
                // card shows a live "/ Nm Ss" cap next to the elapsed time.
                // Only run_command exposes a configurable timeout today; route
                // through the tool's own resolver so the label and the actual
                // monitor share one source of truth.
                val toolTimeoutSeconds: Long? = if (progress.toolName == "run_command") {
                    try {
                        val params = kotlinx.serialization.json.Json
                            .parseToJsonElement(progress.args).jsonObject
                        com.workflow.orchestrator.agent.tools.builtin.RunCommandTool
                            .resolveTimeoutSeconds(params, project)
                    } catch (_: Exception) {
                        null
                    }
                } else null

                // Tool call starting
                dashboard.appendToolCall(
                    toolCallId = progress.toolCallId,
                    toolName = progress.toolName,
                    args = progress.args,
                    status = RichStreamingPanel.ToolCallStatus.RUNNING,
                    toolTimeoutSeconds = toolTimeoutSeconds
                )
            } else {
                // Tool call completed
                val status = if (progress.isError) {
                    RichStreamingPanel.ToolCallStatus.FAILED
                } else {
                    RichStreamingPanel.ToolCallStatus.SUCCESS
                }
                dashboard.updateLastToolCall(
                    status = status,
                    result = progress.result,
                    durationMs = progress.durationMs,
                    toolName = progress.toolName,
                    output = progress.output ?: progress.result.takeIf { it.isNotBlank() },
                    diff = progress.editDiff,
                    toolCallId = progress.toolCallId,
                    // Multimodal-agent Phase 6 — tool-produced image metadata
                    // surfaced to the webview for the "N images attached from
                    // tool" badge. Empty for tools that don't produce images.
                    imageRefs = progress.imageRefs
                )

                // Clear document-extraction progress indicator when read_document finishes
                // (success or error) so the "Extracting document…" row disappears.
                if (progress.toolName == "read_document") {
                    pushDocumentExtractionClear()
                }

                // Show skill banner when use_skill activates a skill
                if (progress.toolName == "use_skill" && !progress.isError) {
                    val skillName = progress.result.substringAfter("'").substringBefore("'")
                    if (skillName.isNotBlank()) {
                        dashboard.showSkillBanner(skillName)
                    }
                }

            }
        }

        // Checkpoint v2: refresh aggregate-diff bar after successful write tools land.
        if (progress.toolName in CHECKPOINT_RELEVANT_TOOLS && progress.durationMs > 0L && !progress.isError) {
            currentSessionId?.let { sid ->
                controllerScope.launch(Dispatchers.EDT + CoroutineName("AgentController.pushAggregateDiff")) {
                    pushAggregateDiff(sid)
                }
            }
        }
    }

    /**
     * Fires when the AgentLoop is about to suspend on [userInputChannel] waiting for
     * the user (consecutive text-only mistakes recovery, plan-mode reply turn).
     *
     * Without this the UI keeps showing the working spinner even though the loop is
     * idle — the coroutine is parked on `channel.receive()` with nothing to log.
     *
     * Mirrors the unlock dance used by `ask_followup_question`: drop busy, enable
     * steering mode (so typed text feeds the channel instead of being queued as a
     * mid-turn steering message), unlock input, surface [reason] so the user knows
     * what happened.
     */
    private fun onLoopAwaitingUserInput(reason: String) {
        LOG.info("AgentController: loop awaiting user input — $reason")
        loopWaitingForInput = true
        invokeLater {
            dashboard.setBusy(false)
            dashboard.setSteeringMode(true)
            dashboard.setInputLocked(false)
            walkthroughService()?.setGenerationPaused(true)
            dashboard.appendStatus(reason, RichStreamingPanel.StatusType.INFO)
            // Mirror the post-exit Failed(EMPTY_RESPONSES / NO_TOOLS_USED) surface:
            // show the Retry button so the user has a one-click affordance identical
            // to the other stall state. Pressing Retry cancels the suspended
            // userInputChannel.receive() via the existing retryLastTask path.
            if (lastTaskText != null) {
                dashboard.showRetryButton("retry", reason)
            }
            dashboard.focusInput()
        }
    }

    private fun walkthroughService(): com.workflow.orchestrator.agent.walkthrough.WalkthroughService? =
        project.getServiceIfCreated(com.workflow.orchestrator.agent.walkthrough.WalkthroughService::class.java)

    @Volatile private var armedWalkthroughQuestionRef: String? = null

    /** Walkthrough "Ask" arms a one-shot step ref; the next user turn is prefixed with it (model text only). */
    fun armWalkthroughQuestionContext(stepRef: String) { armedWalkthroughQuestionRef = stepRef }

    fun focusChatInputForWalkthrough() { dashboard.focusInput() }

    private fun onComplete(result: LoopResult) {
        phraseTimerJob?.cancel()
        phraseTimerJob = null

        val durationMs = System.currentTimeMillis() - taskStartTime

        // Drain the stream batcher before UI flush so no buffered tokens are lost
        flushStream()
        toolStreamBatcher.flush()

        invokeLater {
            // Flush any remaining stream content
            dashboard.flushStreamBuffer()
            // Finalize any open tool chain in the UI (collapse running indicators)
            dashboard.finalizeToolChain()
            // Hide skill banner on task completion
            dashboard.hideSkillBanner()
            // Clear working phrase
            dashboard.setSmartWorkingPhrase("")

            // The spinner-cleanup footer below MUST run even if rendering inside the
            // when-block throws (e.g. a bad completion card breaks appendCompletionCard).
            // Without this guard, the UI was left "working" forever after any UI-side error.
            // Walkthrough auto-finish: must run BEFORE the result-kind dispatch — the
            // SessionHandoff branch early-returns before the cleanup footer (spec §4).
            walkthroughService()?.markGenerationEnded()

            var handledHandoff = false
            try {
            when (result) {
                is LoopResult.Completed -> {
                    dashboard.appendCompletionCard(result.completionData ?: CompletionData(CompletionKind.DONE, result.summary))
                    // Display token usage summary (ported from Cline's cost tracking)
                    if (result.inputTokens > 0 || result.outputTokens > 0) {
                        val inputK = formatTokenCount(result.inputTokens)
                        val outputK = formatTokenCount(result.outputTokens)
                        dashboard.appendStatus(
                            "Used ${inputK} input + ${outputK} output tokens",
                            RichStreamingPanel.StatusType.INFO
                        )
                    }
                    // Gap 21: Show edit stats if any changes were made
                    if (result.linesAdded > 0 || result.linesRemoved > 0) {
                        dashboard.updateEditStats(
                            result.linesAdded,
                            result.linesRemoved,
                            result.filesModified.size
                        )
                    }
                    // Gap 1+14: Pass actual modified files from loop tracking
                    dashboard.completeSession(
                        tokensUsed = result.tokensUsed,
                        iterations = result.iterations,
                        filesModified = result.filesModified,
                        durationMs = durationMs,
                        status = RichStreamingPanel.SessionStatus.SUCCESS
                    )
                    // Re-evaluate the conversation title with Haiku now that we have the
                    // assistant's final response. Replaces provisional user-message-derived
                    // titles with crisp action-oriented ones once per successful turn.
                    evaluateTitleOnCompletion(result.summary)
                }

                is LoopResult.Failed -> {
                    currentSessionId?.let { service.markMonitorsDormantForSession(it) }
                    dashboard.appendError(result.error)
                    if (result.inputTokens > 0 || result.outputTokens > 0) {
                        val inputK = formatTokenCount(result.inputTokens)
                        val outputK = formatTokenCount(result.outputTokens)
                        dashboard.appendStatus(
                            "Used ${inputK} input + ${outputK} output tokens",
                            RichStreamingPanel.StatusType.INFO
                        )
                    }
                    dashboard.completeSession(
                        tokensUsed = result.tokensUsed,
                        iterations = result.iterations,
                        filesModified = result.filesModified,
                        durationMs = durationMs,
                        status = RichStreamingPanel.SessionStatus.FAILED
                    )
                    // Gap 17 + offline: Show retry/continue button based on failure type
                    if (lastTaskText != null) {
                        val isMaxIter = result.reason == FailureReason.MAX_ITERATIONS
                        val isOffline = result.reason == FailureReason.OFFLINE
                        val kind = if (isMaxIter) "continue" else "retry"
                        val caption = when {
                            isMaxIter -> "The agent worked for many iterations without finishing. Click Continue to keep going."
                            isOffline -> "You appear to be offline — the VPN may still be reconnecting after unlock. Click Retry once you're back online."
                            else -> "Something went wrong while running the task."
                        }
                        dashboard.showRetryButton(kind, caption)
                    }
                }

                is LoopResult.Cancelled -> {
                    currentSessionId?.let { service.markMonitorsDormantForSession(it) }
                    dashboard.appendStatus("Task cancelled.", RichStreamingPanel.StatusType.INFO)
                    dashboard.completeSession(
                        tokensUsed = result.tokensUsed,
                        iterations = result.iterations,
                        filesModified = result.filesModified,
                        durationMs = durationMs,
                        status = RichStreamingPanel.SessionStatus.CANCELLED
                    )
                }

                is LoopResult.SessionHandoff -> {
                    // User confirmed the fork on the new_task preview card. Start a fresh
                    // session seeded with the preserved context; the old session is COMPLETED.
                    dashboard.appendStatus(
                        "Continuing in a fresh session with the preserved context.",
                        RichStreamingPanel.StatusType.INFO
                    )
                    if (result.inputTokens > 0 || result.outputTokens > 0) {
                        val inputK = formatTokenCount(result.inputTokens)
                        val outputK = formatTokenCount(result.outputTokens)
                        dashboard.appendStatus(
                            "Previous session used ${inputK} input + ${outputK} output tokens",
                            RichStreamingPanel.StatusType.INFO
                        )
                    }

                    // Reset for the new session. The fork intentionally starts a fresh view;
                    // currentSessionId + contextManager are repopulated by the callbacks below
                    // so the NEXT user message appends instead of wiping (sessionActive stays true).
                    contextManager = null
                    sessionApprovalStore.clear()

                    // Fresh RENDEZVOUS channel so the forked session's own new_task (if any)
                    // can suspend and present the card, and so startFreshSession/keepChatting
                    // send into the live channel (review blocking item #2).
                    userInputChannel = Channel(Channel.RENDEZVOUS)

                    currentJob = service.startHandoffSession(
                        handoffContext = result.context,
                        onStreamChunk = ::onStreamChunk,
                        onToolCall = ::onToolCall,
                        onComplete = ::onComplete,
                        onSessionStarted = { sid ->
                            currentSessionId = sid
                            sessionActive = true
                        },
                        onContextManagerReady = { cm ->
                            contextManager = cm
                            // New session/handoff context is live — refresh the usage bar now.
                            invokeLater { dashboard.refreshContextUsage() }
                        },
                        onHandoffProposed = ::onHandoffProposed,
                        userInputChannel = userInputChannel
                    )
                    handledHandoff = true
                }
            }
            } catch (e: Throwable) {
                LOG.error("onComplete: UI render failed — clearing spinner anyway", e)
            }

            if (handledHandoff) {
                // Handoff started a fresh session — it owns the spinner from here.
                return@invokeLater
            }

            LOG.info("onComplete: clearing busy, unlocking input, disabling steering")
            dashboard.setBusy(false)
            dashboard.setInputLocked(false)
            dashboard.setSteeringMode(false)
            dashboard.focusInput()
            currentJob = null
            // Reset channel state — the loop has finished.
            // Close with a CancellationException cause so any straggling receive() in
            // the loop throws CancellationException (handled by AgentService) instead
            // of ClosedReceiveChannelException (which falls through to the catch-all
            // and surfaces as "task execution failed").
            userInputChannel?.close(CancellationException("agent loop completed"))
            userInputChannel = null
            loopWaitingForInput = false
            // Clear any orphaned steering messages that were queued after the last drain
            activeSessionQueue()?.clearAll()

        }
    }

    // ═══════════════════════════════════════════════════
    //  User actions
    // ═══════════════════════════════════════════════════

    fun cancelTask() {
        LOG.info("AgentController.cancelTask")
        service.cancelCurrentTask()
        // Immediately reset controller state so the next user message starts a fresh loop.
        // Don't wait for onComplete — it fires async and there's a race if the user
        // types a message right after stopping (the message would hit the steering queue
        // or channel path with stale state instead of starting a new loop).
        clearActiveLoopState()
        invokeLater {
            dashboard.setBusy(false)
            dashboard.focusInput()
        }
    }

    /**
     * Clear all transient state associated with the active agent loop iteration.
     * Shared by [cancelTask], [newChat], and [dispose] to keep them in sync.
     */
    private fun clearActiveLoopState() {
        currentJob = null
        pendingApprovalChoice = false
        // Close with a CancellationException cause so a suspended receive() in the loop
        // throws CancellationException (handled by AgentService) instead of
        // ClosedReceiveChannelException (which falls through to the catch-all and
        // surfaces as "task execution failed").
        userInputChannel?.close(CancellationException("agent loop cancelled by user"))
        userInputChannel = null
        loopWaitingForInput = false
        pendingUiMessageOverride.set(null)
        pendingReplyImageRefs.set(emptyList())
        activeSessionQueue()?.clearAll()
        clearStream()
        toolStreamBatcher.flush()   // drain any buffered output on cancel
        firstFlushSeen.clear()
    }

    /**
     * Kill background processes owned by [leavingSessionId] before a session transition.
     *
     * If [AgentSettings.suppressBackgroundKillConfirmation] is false and there are running
     * processes, shows a confirmation dialog listing the affected processes.
     *
     * @return true if the transition should proceed (no processes, or user confirmed), false
     *         if the user cancelled.
     */
    private fun killBackgroundsOnTransition(
        leavingSessionId: String,
        transitionLabel: String
    ): Boolean {
        val pool = com.workflow.orchestrator.agent.tools.background.BackgroundPool.getInstance(project)
        val running = pool.list(leavingSessionId)
        if (running.isEmpty()) {
            // Task 8 — a pure-monitor session (monitors but no bg processes) still leaks its
            // MonitorManager + MonitorPool entry; clean monitors before the early return
            // (disposeMonitorsForSession is idempotent).
            service.disposeMonitorsForSession(leavingSessionId)
            // Task 6F — clear persisted monitor specs + pending notifications so a new session
            // does not inherit the leaving session's monitors.
            service.clearPersistedMonitors(leavingSessionId)
            return true
        }

        val settings = AgentSettings.getInstance(project).state
        if (!settings.suppressBackgroundKillConfirmation) {
            val message = buildString {
                appendLine("$transitionLabel will kill ${running.size} running background processes:")
                running.forEach { h ->
                    appendLine("  • ${h.bgId} (${h.kind}: \"${h.label.take(80)}\")")
                }
                appendLine()
                appendLine("Continue?")
            }
            val choice = com.intellij.openapi.ui.Messages.showYesNoDialog(
                project,
                message,
                "Background Processes",
                com.intellij.openapi.ui.Messages.getWarningIcon()
            )
            if (choice != com.intellij.openapi.ui.Messages.YES) return false
        }
        pool.killAll(leavingSessionId)
        // Task 8 — drop the leaving session's monitor coordinator + kill its live monitor sources.
        service.disposeMonitorsForSession(leavingSessionId)
        // Task 6F — clear persisted monitor specs + pending notifications so a new session
        // does not inherit the leaving session's monitors.
        service.clearPersistedMonitors(leavingSessionId)
        return true
    }

    fun newChat() {
        LOG.info("AgentController.newChat")
        val leaving = currentSessionId
        if (leaving != null && !killBackgroundsOnTransition(leaving, "Starting a new chat")) return
        walkthroughService()?.endTour(byUser = false)
        resetForNewChat()
    }

    /**
     * Narrow public API for starting a new PR review session from outside the :agent module
     * (e.g. from PrDetailPanel in :pullrequest).
     *
     * Resets to a clean session, then submits [initialMessage] as the first user turn.
     * The [sessionTag] is logged for observability but does not alter agent behavior —
     * the initial message itself carries all PR context.
     *
     * Why a wrapper: AgentController is not a service and cannot be injected across modules.
     * Callers obtain the controller via AgentControllerRegistry.getInstance(project).controller.
     * This wrapper hides the two-step reset + execute behind a single call site.
     *
     * @param persona  Currently unused at the controller level; persona guidance is embedded
     *                 in [initialMessage] by PrReviewTaskBuilder. Future polish may wire this
     *                 to a persona selector in the dashboard.
     * @param initialMessage  The full prompt built by PrReviewTaskBuilder.
     * @param sessionTag  Opaque tag logged for tracing (e.g. "pr-review:PROJ/repo/PR-42").
     */
    fun startPrReviewSession(persona: String, initialMessage: String, sessionTag: String) {
        LOG.info("AgentController.startPrReviewSession: tag=$sessionTag persona=$persona")
        resetForNewChat()
        executeTask(
            task = initialMessage,
            displayText = "Running AI review… (session tag: $sessionTag)",
        )
    }

    /**
     * Entry point for an accepted inbound cross-IDE delegation (Plan 2 Task 4 + busy-case
     * follow-up).
     *
     * Routes the delegation through the IDE-B agent tab so it runs as a NORMAL FOREGROUND
     * session — full agent (tools + IDE-B approval gate + streaming), NOT a headless loop.
     * There is NO background execution: a delegated session runs only while it is the
     * focused session.
     *
     * Surfacing decision (via [DelegatedSessionSurface.decide]):
     *  - idle tab ([DelegatedSurface.RUN_NOW]) → run now ([runDelegatedNow]); outcome [STARTED].
     *  - busy tab ([DelegatedSurface.QUEUE_INCOMING]) → push an "incoming delegation" prompt to
     *    the webview top bar (`{key, delegatorRepo, deadlineEpochMs}`) and suspend on a
     *    [CompletableDeferred] bounded by [ACCEPT_WINDOW_MS]. If the human clicks Start within
     *    the window ([startIncomingDelegation] completes the deferred `true`) → [runDelegatedNow]
     *    AS A NEW CHAT; outcome [STARTED]. If the window elapses → clear the prompt; outcome
     *    [DECLINED_TIMEOUT].
     *
     * Called by [DelegationInboundService.handleConnect] OFF the EDT (socket coroutine). This is
     * a `suspend fun` so the busy-case wait happens on the socket coroutine; the dashboard/UI
     * touches inside [runDelegatedNow] and the push helpers hop onto the EDT themselves.
     *
     * [onSessionStarted] fires (off the EDT, from the service) once the local session id is
     * known — `handleConnect` uses it to resolve the sid for the inbound read-loop. It is only
     * invoked once a session actually starts (idle tab, or Start clicked); never on a
     * [DECLINED_TIMEOUT] outcome.
     */
    /**
     * Snapshot the in-flight task descriptor for a busy-decline (PART 2). Best-effort: reads the
     * live `currentSessionId`, its delegated-by metadata (`currentSessionState()?.delegated`,
     * the pattern used at the IDE-B short-circuit), and its human title from the persisted
     * HistoryItem. Any field that can't be resolved is left null; the inbound reason composer
     * falls back to the generic wording when the descriptor (or a field) is absent.
     */
    /**
     * ADVISORY busy snapshot for the doorbell liveness probe. Returns true iff an agent loop is
     * ACTIVELY RUNNING right now — the SAME verdict the inbound busy gate uses ([decideIncomingBusy]
     * over `currentJob?.isActive`). A merely loaded-but-idle session is NOT busy. Point-in-time
     * (TOCTOU) — the caller surfaces it as a hint only. Cheap + side-effect-free so it is safe on
     * the doorbell's liveness path.
     */
    fun isAgentBusy(): Boolean = currentJob?.isActive == true

    private fun currentBusyInfo(): BusyInfo {
        val sid = currentSessionId
        val delegated = runCatching { service.currentSessionState()?.delegated }.getOrNull()
        val title = sid?.let { runCatching { service.currentSessionTitle(it) }.getOrNull() }
        return BusyInfo(
            inFlightSessionId = sid,
            inFlightTitle = title,
            inFlightDelegatorSessionId = delegated?.delegatorSessionId,
            inFlightDelegatorRepo = delegated?.delegatorRepo,
        )
    }

    suspend fun startDelegatedSession(
        request: String,
        metadata: com.workflow.orchestrator.agent.session.DelegationMetadata,
        replyWith: suspend (com.workflow.orchestrator.core.delegation.DelegationMessage) -> Unit,
        onResult: suspend (com.workflow.orchestrator.core.delegation.DelegationMessage.Result) -> Unit,
        onSessionStarted: ((String) -> Unit)? = null,
        onBusy: ((BusyInfo) -> Unit)? = null,
    ): DelegatedStartOutcome {
        // Bug B: "busy" for an INCOMING delegation means an agent loop is ACTIVELY RUNNING, not
        // merely that a session is loaded in the tab. A completed session (delegated or
        // interactive) leaves currentSessionId set until the next "New Chat" — gating on it made
        // every delegation after the first dead-end in the human-Start accept window
        // (declined_timeout). Gate on the live job only; decideIncomingBusy pins this contract.
        //
        // BUG #3: fold the busy CHECK and the slot CLAIM into a single atomic step. The job-based
        // busy verdict alone has a check-then-act gap — `currentJob` is assigned LATER on the EDT
        // coroutine in runDelegatedNow (onJobCreated), and DelegationServer.acceptLoop launches
        // inbound handlers WITHOUT joining, so two near-simultaneous delegations both read the job
        // inactive and both decide RUN_NOW. tryReserve makes exactly one of N concurrent claimants
        // win RUN_NOW; the rest fall through to QUEUE_INCOMING. The reservation is released by
        // runDelegatedNow once currentJob is assigned AND on every failure path (so a failed start
        // can't wedge the gate). decideIncomingBusy is still consulted (do not regress Fix B): a
        // genuinely running loop refuses the claim; a completed-but-loaded session does not.
        // PART 2: snapshot the in-flight task descriptor BEFORE any reservation/reset mutates state,
        // so a busy decline can name what IDE-B is actually busy with (and echo the delegator session
        // id of the in-flight task). Cheap and side-effect-free; only consumed on the decline path.
        val busyInfo = currentBusyInfo()
        val reservedRunNow = startReservation.tryReserve(
            busy = decideIncomingBusy(
                jobActive = currentJob?.isActive == true,
                sessionLoaded = currentSessionId != null,
            )
        )
        // A lost reservation (live job OR another delegation mid-start) ⇒ busy ⇒ QUEUE_INCOMING.
        val surfaceBusy = !reservedRunNow
        return when (com.workflow.orchestrator.agent.delegation.DelegatedSessionSurface.decide(surfaceBusy)) {
            com.workflow.orchestrator.agent.delegation.DelegatedSurface.RUN_NOW -> {
                // We hold the reservation; runDelegatedNow releases it (onJobCreated / failure).
                runDelegatedNow(request, metadata, replyWith, onResult, onSessionStarted)
                DelegatedStartOutcome.STARTED
            }
            com.workflow.orchestrator.agent.delegation.DelegatedSurface.QUEUE_INCOMING -> {
                // No background execution: surface a top-bar prompt and wait for the human to
                // click Start within the configured accept window. The deferred is completed by
                // startIncomingDelegation(key); the window is bounded by withTimeoutOrNull.
                // Read the configured window from settings so it is user-adjustable.
                // ACCEPT_WINDOW_MS is the default value and a reference sentinel — the runtime
                // value comes from effectiveAcceptWindowMs() (clamped to [10, 59] s to stay
                // below IDE-A's 60 s connectAndAwaitAccept timeout).
                val acceptWindowMs = com.workflow.orchestrator.core.settings.PluginSettings
                    .getInstance(project).state.effectiveAcceptWindowMs()
                val key = metadata.delegatorSessionId
                val deadlineEpochMs = System.currentTimeMillis() + acceptWindowMs
                val startGate = CompletableDeferred<Boolean>()
                pendingIncomingStarts[key] = startGate
                LOG.info(
                    "Incoming delegation from ${metadata.delegatorRepo} arrived while busy — " +
                        "surfacing top-bar prompt (key=$key, ${acceptWindowMs}ms window)"
                )
                pushIncomingDelegation(key, metadata.delegatorRepo, deadlineEpochMs)
                try {
                    // I4: timing core lives in the pure, unit-tested awaitIncomingStart helper.
                    val started = awaitIncomingStart(startGate, acceptWindowMs)
                    if (started) {
                        // BUG #3: the human clicked Start → this runs AS A NEW CHAT and will assign
                        // currentJob later on the EDT coroutine, so it must hold the reservation just
                        // like the direct RUN_NOW path. Claim it now; runDelegatedNow releases it on
                        // onJobCreated / failure. (A deliberate human Start interrupts whatever was
                        // running via resetForNewChat, and release is idempotent, so we proceed even
                        // in the rare case another start briefly holds the slot.)
                        startReservation.tryReserve(busy = false)
                        runDelegatedNow(request, metadata, replyWith, onResult, onSessionStarted)
                        DelegatedStartOutcome.STARTED
                    } else {
                        LOG.info("Incoming delegation key=$key declined (accept window elapsed)")
                        // PART 2: hand the in-flight descriptor to the inbound decline composer so the
                        // reason names IDE-B's busy task (and echoes its delegator session id).
                        onBusy?.invoke(busyInfo)
                        DelegatedStartOutcome.DECLINED_TIMEOUT
                    }
                } finally {
                    pendingIncomingStarts.remove(key)
                    pushIncomingDelegationCleared(key)
                }
            }
        }
    }

    /**
     * Complete the pending Start gate for a busy-case incoming delegation. Called from the
     * `_startIncomingDelegation` JS bridge when the human clicks Start on the top-bar prompt.
     * No-op if no delegation is pending for [key] (already started, already declined/expired,
     * or a stale/duplicate click).
     */
    fun startIncomingDelegation(key: String) {
        val gate = pendingIncomingStarts[key]
        if (gate == null) {
            LOG.info("startIncomingDelegation: no pending incoming delegation for key=$key (expired or duplicate)")
            return
        }
        // complete() is idempotent — returns false on a second call. The waiter in
        // startDelegatedSession removes the entry + clears the prompt in its finally block.
        gate.complete(true)
    }

    /**
     * Push the busy-case "incoming delegation" prompt to the webview top bar. The React
     * component (top-bar button + countdown) is wired in a later task; the `if (window._x)`
     * guard makes this a safe no-op until then.
     */
    private fun pushIncomingDelegation(key: String, delegatorRepo: String, deadlineEpochMs: Long) {
        controllerScope.launch(Dispatchers.EDT) {
            val payload = buildString {
                append("{\"key\":")
                append(historyJson.encodeToString(key))
                append(",\"delegatorRepo\":")
                append(historyJson.encodeToString(delegatorRepo))
                append(",\"deadlineEpochMs\":")
                append(deadlineEpochMs)
                append("}")
            }
            dashboard.callJs("if (window._incomingDelegation) window._incomingDelegation($payload)")
        }
    }

    /** Clear the busy-case incoming-delegation prompt for [key] (started or timed out). */
    private fun pushIncomingDelegationCleared(key: String) {
        controllerScope.launch(Dispatchers.EDT) {
            dashboard.callJs(
                "if (window._incomingDelegationCleared) " +
                    "window._incomingDelegationCleared(${historyJson.encodeToString(key)})"
            )
        }
    }

    /**
     * Run an accepted delegation as a foreground session. Mirrors the controller-driven
     * [LoopResult.SessionHandoff] / [startHandoffSession] wiring: reset the dashboard on the
     * EDT, then start the session via [AgentService.startDelegatedSession] (which launches
     * the ReAct loop on IO). [currentJob] is captured via `onJobCreated`; `currentSessionId`
     * + `sessionActive` are set in `onSessionStarted` so steering/short-circuit paths see a
     * live session, and so the inbound read-loop's sid (set on the service side via the same
     * `onSessionStarted`) is consistent with the controller's view.
     */
    /**
     * Leg (a) + live-session wiring for a freshly-started delegated session. Sets the
     * controller's session pointers, [viewedSessionId] (so gated webview pushes fire), the
     * top-bar DelegationBanner, and narrates the INCOMING task as the opening bubble using
     * the delegator's REPO NAME (matching the persisted uiMessageOverride exactly). Extracted
     * from [runDelegatedNow] so its body stays compact (BUG #3 reservation-wiring source pins).
     */
    private fun onDelegatedSessionStarted(
        sid: String,
        metadata: com.workflow.orchestrator.agent.session.DelegationMetadata,
        request: String,
        onSessionStarted: ((String) -> Unit)?,
    ) {
        currentSessionId = sid
        sessionActive = true
        // Treat the delegated session as the VIEWED session so gated webview pushes (the
        // question banner AND the delegation conversation cards) fire — previously
        // viewedSessionId stayed null on the delegated start, suppressing both.
        viewedSessionId = sid
        pushActiveSessionDelegated(metadata)
        // Leg (a): resetForNewChat() already cleared the panel; push the task as the opening
        // bubble flagged delegated AT CREATION so it (and every subsequent assistant/tool
        // bubble for the session's lifetime) renders the delegated tint + accent stripe +
        // "delegated · {repo}" pill. The repo NAME — never the raw "ide-$pid" — is the pill.
        dashboard.startSessionDelegated(
            com.workflow.orchestrator.agent.AgentService.delegatedIncomingTaskText(metadata, request),
            metadata.delegatorRepo,
        )
        onSessionStarted?.invoke(sid)
    }

    /**
     * Leg (d): wrap the socket [onResult] so the delegation RESULT card renders on IDE-B's
     * own panel (status-colored, with duration + summary, repo-named) BEFORE the result is
     * shipped over the wire. Delivery is unchanged — the original [onResult] is always called.
     */
    private fun wrapDelegatedOnResult(
        metadata: com.workflow.orchestrator.agent.session.DelegationMetadata,
        onResult: suspend (com.workflow.orchestrator.core.delegation.DelegationMessage.Result) -> Unit,
    ): suspend (com.workflow.orchestrator.core.delegation.DelegationMessage.Result) -> Unit =
        { result ->
            currentSessionId?.let { sid -> pushDelegatedResult(sid, metadata.delegatorRepo, result) }
            onResult(result)
        }

    /**
     * #1 reconciliation — terminal/finalize callback for a DELEGATED loop.
     *
     * Wiring the bundle's [onComplete] makes the controller's completion handler fire for delegated
     * sessions too — but the delegated path ALSO renders the Bug-2 delegation RESULT card (via
     * [wrapDelegatedOnResult] → [pushDelegatedResult] on the socket `onResult`). To avoid TWO
     * terminal cards, the delegated path uses THIS finalizer instead of the generic [onComplete]:
     * it performs the spinner / active-tool-chain / busy-state finalization (so the session stops
     * "looking stuck") but SUPPRESSES the generic completion/failure card — the repo-named,
     * delegation-styled result card is the single terminal card.
     *
     * The socket result delivery is unchanged: [AgentService.startDelegatedSession] /
     * [AgentService.resumeDelegatedSession] still complete `loopResultDeferred` and invoke
     * `onResult` exactly as before; this finalizer runs alongside that, never in place of it.
     */
    private fun delegatedFinalizeOnComplete(result: LoopResult) {
        phraseTimerJob?.cancel()
        phraseTimerJob = null
        // Drain pre-bridge buffers so no buffered tokens are lost before finalize.
        flushStream()
        toolStreamBatcher.flush()
        invokeLater {
            try {
                dashboard.flushStreamBuffer()
                // Collapse running tool indicators / spinner — the session is done.
                dashboard.finalizeToolChain()
                dashboard.hideSkillBanner()
                dashboard.setSmartWorkingPhrase("")
            } catch (e: Throwable) {
                LOG.error("delegatedFinalizeOnComplete: UI finalize failed — clearing spinner anyway", e)
            }
            // Deliberately NO appendCompletionCard / completeSession here: the delegation RESULT
            // card (pushDelegatedResult) is the terminal card for a delegated session.
            LOG.info("delegatedFinalizeOnComplete: clearing busy/steering for delegated session")
            dashboard.setBusy(false)
            dashboard.setInputLocked(false)
            dashboard.setSteeringMode(false)
            currentJob = null
            userInputChannel?.close(CancellationException("delegated agent loop completed"))
            userInputChannel = null
            loopWaitingForInput = false
            activeSessionQueue()?.clearAll()
        }
    }

    private fun runDelegatedNow(
        request: String,
        metadata: com.workflow.orchestrator.agent.session.DelegationMetadata,
        replyWith: suspend (com.workflow.orchestrator.core.delegation.DelegationMessage) -> Unit,
        onResult: suspend (com.workflow.orchestrator.core.delegation.DelegationMessage.Result) -> Unit,
        onSessionStarted: ((String) -> Unit)? = null,
    ) {
        // Reset on the EDT (dashboard touch), then start the foreground session. The service
        // launches the agent loop on IO; we capture the Job + session id via callbacks.
        controllerScope.launch(Dispatchers.EDT + CoroutineName("AgentController.startDelegatedSession")) {
            // C1: if this fire-and-forget EDT launch throws before onSessionStarted fires
            // (resetForNewChat / startDelegatedSession blowing up, or scope cancelled on project
            // close), the sid deferred in handleConnect never completes. Make the failure
            // OBSERVABLE here (log) — handleConnect's bounded await (SESSION_START_TIMEOUT_MS)
            // then fires the clean session_start_timeout FAILED rather than hanging forever.
            // Re-throw CancellationException so coroutine cancellation still propagates.
            try {
                resetForNewChat()
                // #7/#8 (documented, intentional NOT-wired):
                //  - Inbound delegated tasks bypass the local USER_PROMPT_SUBMIT hook on purpose:
                //    the prompt originates from a REMOTE IDE, not a local user keystroke, so the
                //    local prompt-submit hook does not apply (it runs only in executeTaskInternal).
                //  - Local mid-turn STEERING of a delegated session is unsupported: cross-IDE
                //    interaction flows through the routed question/answer channel
                //    (DelegationInboundService), not the local steering queue. The loop resolves
                //    its queue directly via queueForSession(sessionId) inside executeTask/
                //    resumeSession; there is no messageQueue bundle field to forward (removed in
                //    the unified-queue migration).
                //
                // SINGLE SOURCE OF TRUTH: source the full callback set from the SAME builder the
                // interactive path uses, then .copy() ONLY the two delegated-specific overrides:
                //  - onSessionStarted → banner + viewedSessionId wiring (onDelegatedSessionStarted)
                //  - onComplete → spinner/tool-chain finalize WITHOUT the generic completion card
                //    (#1: the delegation RESULT card is the single terminal card).
                val ui = buildSessionUiCallbacks().copy(
                    onSessionStarted = { sid -> onDelegatedSessionStarted(sid, metadata, request, onSessionStarted) },
                    onComplete = ::delegatedFinalizeOnComplete,
                )
                service.startDelegatedSession(
                    request = request,
                    delegationMetadata = metadata,
                    replyWith = replyWith,
                    // Leg (d): wrap onResult to render the result card on IDE-B's panel FIRST,
                    // then call the original socket onResult (delivery unchanged).
                    onResult = wrapDelegatedOnResult(metadata, onResult),
                    callbacks = ui,
                    onJobCreated = { job ->
                        // BUG #3: assign currentJob FIRST, then release the reservation. From here on
                        // a concurrent inbound delegation reads currentJob.isActive == true via
                        // decideIncomingBusy and queues — the live-job gate has taken over, so the
                        // reservation's job (covering the assign gap) is done. Order matters: release
                        // before the assignment would briefly leave the gate open with no live job.
                        currentJob = job
                        startReservation.release()
                    },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                LOG.error("runDelegatedNow: delegated session start failed on EDT — " +
                    "handleConnect's sid await will time out and reply FAILED", e)
            } finally {
                // BUG #3 safety-net: release on EVERY exit path so a failed/cancelled start (or one
                // where onJobCreated never fired) can't wedge the gate closed forever. Idempotent —
                // a harmless no-op on the happy path where onJobCreated already released.
                startReservation.release()
            }
        }
    }

    /**
     * Entry point for RESUMING a previously-completed inbound delegated session and continuing it
     * with a follow-up turn (Fix 3 — true continuation). The resurrection counterpart of
     * [startDelegatedSession]: same busy-gate ([decideIncomingBusy]) and live "Delegated by…" banner
     * ([pushActiveSessionDelegated]), but it re-opens the SAME persisted session via
     * [AgentService.resumeDelegatedSession] (which drives `resumeSession`) instead of starting a fresh
     * one — so it must NOT `resetForNewChat()` (that would wipe the session being resumed).
     *
     * Called by [com.workflow.orchestrator.agent.delegation.DelegationInboundService.handleChannelResume]
     * OFF the EDT (socket coroutine). The inbound consent dialog has already been skipped upstream (the
     * human accepted this channel when the delegation was first established).
     *
     * Busy semantics mirror the incoming-delegation gate exactly: if an agent loop is ACTIVELY running
     * another task, decline gracefully ([DelegatedStartOutcome.DECLINED_TIMEOUT]) — never hijack it.
     * A merely loaded-but-idle session (including the very session we're resuming) is NOT busy.
     */
    suspend fun resumeDelegatedSession(
        sessionId: String,
        userTurnText: String,
        metadata: com.workflow.orchestrator.agent.session.DelegationMetadata,
        replyWith: suspend (com.workflow.orchestrator.core.delegation.DelegationMessage) -> Unit,
        onResult: suspend (com.workflow.orchestrator.core.delegation.DelegationMessage.Result) -> Unit,
        onSessionStarted: ((String) -> Unit)? = null,
        onBusy: ((BusyInfo) -> Unit)? = null,
    ): DelegatedStartOutcome {
        // Same busy rule as the incoming-delegation gate (Bug B): "busy" = an agent loop is actively
        // running right now, not merely that a session sits loaded in the tab.
        //
        // BUG #3: the resume path shares the SAME check-then-act gap as startDelegatedSession —
        // runResumedDelegatedNow assigns currentJob LATER on the EDT coroutine (onJobCreated), so two
        // concurrent resumes (or a resume racing an inbound start) could both read the job inactive
        // and both launch a loop, clobbering currentJob. Claim the SAME atomic reservation so exactly
        // one delegation start/resume is in-flight at a time; runResumedDelegatedNow releases it on
        // onJobCreated AND on failure. decideIncomingBusy still gates the live-job case (Fix B / a
        // loaded-but-idle session — including the one being resumed — is NOT busy).
        // PART 2: snapshot the in-flight descriptor before the reservation, mirroring the
        // fresh-connect gate, so a busy decline names what IDE-B is busy with.
        val busyInfo = currentBusyInfo()
        val reserved = startReservation.tryReserve(
            busy = decideIncomingBusy(
                jobActive = currentJob?.isActive == true,
                sessionLoaded = currentSessionId != null,
            )
        )
        if (!reserved) {
            // Busy (live loop OR another delegation mid-start). Decline gracefully —
            // handleChannelResume maps this to a clear "busy" SessionClosed. Nothing to release: a
            // refused tryReserve never changed reservation state.
            LOG.info("resumeDelegatedSession: IDE-B tab busy with another task — declining resume of $sessionId")
            onBusy?.invoke(busyInfo)
            return DelegatedStartOutcome.DECLINED_TIMEOUT
        }
        // We hold the reservation; runResumedDelegatedNow releases it (onJobCreated / failure).
        runResumedDelegatedNow(sessionId, userTurnText, metadata, replyWith, onResult, onSessionStarted)
        return DelegatedStartOutcome.STARTED
    }

    /**
     * Continue a resumed delegation as a foreground session. Mirrors [runDelegatedNow] but drives
     * [AgentService.resumeDelegatedSession] (re-opening the persisted session) and does NOT
     * `resetForNewChat()`. [currentJob]/[currentSessionId]/[sessionActive] + the delegated banner are
     * wired through the same callbacks so steering/short-circuit paths see a live delegated session.
     */
    private fun runResumedDelegatedNow(
        sessionId: String,
        userTurnText: String,
        metadata: com.workflow.orchestrator.agent.session.DelegationMetadata,
        replyWith: suspend (com.workflow.orchestrator.core.delegation.DelegationMessage) -> Unit,
        onResult: suspend (com.workflow.orchestrator.core.delegation.DelegationMessage.Result) -> Unit,
        onSessionStarted: ((String) -> Unit)? = null,
    ) {
        controllerScope.launch(Dispatchers.EDT + CoroutineName("AgentController.resumeDelegatedSession")) {
            try {
                // #7/#8 (documented, intentional NOT-wired) — same rationale as runDelegatedNow:
                // resumed delegated turns originate from the remote IDE (no local USER_PROMPT_SUBMIT
                // hook), and local mid-turn steering is unsupported (routed Q&A channel only).
                //
                // SINGLE SOURCE OF TRUTH: same builder as the interactive path, .copy()-ing ONLY the
                // two delegated overrides (banner/session wiring + #1 single-terminal-card finalize).
                val ui = buildSessionUiCallbacks().copy(
                    onSessionStarted = { sid ->
                        currentSessionId = sid
                        sessionActive = true
                        // Light up the "Delegated by {IDE} from {repo}" banner for this resumed session.
                        pushActiveSessionDelegated(metadata)
                        onSessionStarted?.invoke(sid)
                    },
                    onComplete = ::delegatedFinalizeOnComplete,
                )
                service.resumeDelegatedSession(
                    sessionId = sessionId,
                    userTurnText = userTurnText,
                    delegationMetadata = metadata,
                    replyWith = replyWith,
                    // #1: render the single repo-named delegation RESULT card here too (delivery
                    // unchanged — wrapDelegatedOnResult always calls the original onResult).
                    onResult = wrapDelegatedOnResult(metadata, onResult),
                    callbacks = ui,
                    onJobCreated = { job ->
                        // BUG #3: assign currentJob FIRST, then release — same handoff-to-live-job
                        // ordering as runDelegatedNow.
                        currentJob = job
                        startReservation.release()
                    },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                LOG.error("runResumedDelegatedNow: resumed delegated session failed on EDT — " +
                    "handleChannelResume's sid await will time out and reply a clear error", e)
            } finally {
                // BUG #3 safety-net: idempotent release on every exit path so a failed/cancelled
                // resume can't wedge the gate.
                startReservation.release()
            }
        }
    }

    /**
     * Reset all controller and dashboard state for a fresh session.
     * Does NOT show history — callers decide the next view.
     */
    private fun resetForNewChat() {
        // Reset all service-level state (plan mode, tools, processes, active task)
        service.resetForNewChat()

        // Reset per-turn attachment counters (new session = new turn).
        attachmentIngest?.resetTurn()

        // Reset controller state
        clearActiveLoopState()
        phraseTimerJob?.cancel()
        phraseTimerJob = null
        recentToolCalls.clear()
        currentHaikuTitle = null
        lastStreamSnippet = ""
        contextManager?.clearActivePlanPath()
        contextManager = null
        sessionApprovalStore.clear()
        taskStartTime = 0L
        subagentAccumIn = 0L
        subagentAccumOut = 0L
        lastParentStats = null
        lastTaskText = null
        lastDisplayText = null
        lastDisplayMentionsJson = null
        originalTaskText = null
        currentSessionId = null
        sessionActive = false
        currentPlanData = null
        accumulatedPlanText = ""
        pendingApproval?.cancel()
        pendingApproval = null
        pendingApprovalToolName = null
        viewedSessionId = null
        // Invalidate in-flight showSession pushes (W4-B2 review Important #1) so a late
        // load can't repopulate the freshly reset chat view.
        showSessionGeneration.incrementAndGet()

        // Reset ALL dashboard UI components to clean state
        dashboard.reset()                                          // Clear chat messages + replay log
        dashboard.hideSkillBanner()                                // Dismiss any active skill banner
        dashboard.setBusy(false)                                   // Stop spinner
        dashboard.setInputLocked(false)                            // Unlock input bar
        dashboard.setPlanMode(false)                                // Exit plan mode in UI
        dashboard.setSteeringMode(false)                           // Exit steering mode
        dashboard.updateEditStats(0, 0, 0)                    // Reset edit counters
        // Clear aggregate-diff bar on new chat (C2 — was stale from previous session).
        dashboard.updateAggregateDiff(
            taskEventJson.encodeToString(
                com.workflow.orchestrator.agent.checkpoint.AggregateDiff(0, 0, emptyList())
            )
        )
        dashboard.updateProgress("", 0, 0)                    // Reset token budget bar
        dashboard.setSmartWorkingPhrase("")                         // Clear working phrase
        dashboard.setSessionTitle("")                               // Clear conversation title
        pushActiveSessionDelegated(null)                            // Clear delegated-session banner (was stale)
        dashboard.finalizeToolChain()                               // Collapse any open tool chain
        dashboard.setTasks("[]")                                   // Clear task list (prevent stale state leak)
        dashboard.focusInput()                                      // Focus the input bar
    }

    // ═══════════════════════════════════════════════════
    //  Session history — list, delete, favorite, navigate
    // ═══════════════════════════════════════════════════

    private val historyJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    /**
     * Load the global session index and push it to the webview as the history list.
     * File I/O runs on Dispatchers.IO to avoid blocking the CEF thread.
     */
    fun showHistory() {
        // Invalidate any in-flight showSession push — it must not yank the user back
        // out of the history view (W4-B2 review Important #1; viewedSessionId is
        // intentionally NOT cleared here, so the generation is the only guard).
        showSessionGeneration.incrementAndGet()
        val basePath = project.basePath ?: return
        val baseDir = ProjectIdentifier.agentDir(basePath)
        controllerScope.launch(Dispatchers.IO) {
            val items = MessageStateHandler.loadGlobalIndex(baseDir)
            val json = historyJson.encodeToString(items)
            invokeLater { dashboard.loadSessionHistory(json) }
        }
    }

    /**
     * Delete a session from disk and refresh the history list.
     *
     * If the deleted session is the currently-viewed one, also clears
     * [viewedSessionId] and dismisses the resume bar so the user isn't left
     * looking at — or able to "Resume" — a session whose files no longer
     * exist (resume would silently no-op since [AgentService.resumeSession]
     * bails when the session directory is missing).
     */
    fun handleDeleteSession(sessionId: String) {
        if (!killBackgroundsOnTransition(sessionId, "Deleting this session")) return
        val basePath = project.basePath ?: return
        val baseDir = ProjectIdentifier.agentDir(basePath)
        val wasViewed = viewedSessionId == sessionId
        controllerScope.launch(Dispatchers.IO) {
            MessageStateHandler.deleteSession(baseDir, sessionId)
            val items = MessageStateHandler.loadGlobalIndex(baseDir)
            val json = historyJson.encodeToString(items)
            invokeLater {
                if (wasViewed) {
                    viewedSessionId = null
                    dashboard.hideResumeBar()
                    dashboard.reset()
                }
                dashboard.loadSessionHistory(json)
            }
        }
    }

    /**
     * Toggle the favorite flag on a session and refresh the history list.
     */
    fun handleToggleFavorite(sessionId: String) {
        val basePath = project.basePath ?: return
        val baseDir = ProjectIdentifier.agentDir(basePath)
        controllerScope.launch(Dispatchers.IO) {
            MessageStateHandler.toggleFavorite(baseDir, sessionId)
            val items = MessageStateHandler.loadGlobalIndex(baseDir)
            val json = historyJson.encodeToString(items)
            invokeLater { dashboard.loadSessionHistory(json) }
        }
    }

    /**
     * Start a fresh session from the history view.
     * Resets state without showing history (avoids history→chat flicker).
     */
    fun handleStartNewSession() {
        val leaving = currentSessionId
        if (leaving != null && !killBackgroundsOnTransition(leaving, "Starting a new chat")) return
        resetForNewChat()
        dashboard.showChatView()
    }

    /**
     * Bulk-delete multiple sessions from disk and refresh the history list.
     *
     * Mirrors the single-session safeguards in [handleDeleteSession]:
     *  - if the currently-running session is in the selection, prompt to kill
     *    its background processes first; abort the whole delete if the user
     *    declines.
     *  - if the currently-viewed session is in the selection, clear
     *    [viewedSessionId] and reset the chat view after the delete so the
     *    user isn't looking at a session whose files have just been removed.
     *
     * @param sessionIdsJson JSON array of session ID strings.
     */
    fun handleBulkDeleteSessions(sessionIdsJson: String) {
        val basePath = project.basePath ?: return
        val baseDir = ProjectIdentifier.agentDir(basePath)
        val ids: List<String> = try {
            historyJson.decodeFromString(sessionIdsJson)
        } catch (e: Exception) {
            LOG.warn("Failed to parse bulk delete session IDs", e)
            return
        }
        val running = currentSessionId
        if (running != null && running in ids &&
            !killBackgroundsOnTransition(running, "Deleting ${ids.size} session(s)")
        ) return
        val viewedInSelection = viewedSessionId?.let { it in ids } ?: false
        controllerScope.launch(Dispatchers.IO) {
            for (id in ids) {
                MessageStateHandler.deleteSession(baseDir, id)
            }
            val items = MessageStateHandler.loadGlobalIndex(baseDir)
            val json = historyJson.encodeToString(items)
            invokeLater {
                if (viewedInSelection) {
                    viewedSessionId = null
                    dashboard.hideResumeBar()
                    dashboard.reset()
                }
                dashboard.loadSessionHistory(json)
            }
        }
    }

    /**
     * Export a single session as markdown and copy to clipboard.
     */
    fun handleExportSession(sessionId: String) {
        val basePath = project.basePath ?: return
        val baseDir = ProjectIdentifier.agentDir(basePath)
        controllerScope.launch(Dispatchers.IO) {
            val markdown = formatSessionAsMarkdown(baseDir, sessionId)
            if (markdown != null) {
                com.workflow.orchestrator.core.ui.ClipboardUtil.copyToClipboard(markdown)
                invokeLater {
                    dashboard.showToast("Session exported to clipboard", "SUCCESS", 3000)
                }
            }
        }
    }

    /**
     * Export all sessions as markdown and copy to clipboard.
     */
    fun handleExportAllSessions() {
        val basePath = project.basePath ?: return
        val baseDir = ProjectIdentifier.agentDir(basePath)
        controllerScope.launch(Dispatchers.IO) {
            val items = MessageStateHandler.loadGlobalIndex(baseDir)
            if (items.isEmpty()) return@launch
            val parts = items.mapNotNull { item ->
                formatSessionAsMarkdown(baseDir, item.id)
            }
            if (parts.isNotEmpty()) {
                val combined = parts.joinToString("\n\n---\n\n")
                com.workflow.orchestrator.core.ui.ClipboardUtil.copyToClipboard(combined)
                invokeLater {
                    dashboard.showToast("${parts.size} sessions exported to clipboard", "SUCCESS", 3000)
                }
            }
        }
    }

    /**
     * Format a session's UI messages as a markdown string.
     * Returns null if the session has no messages.
     *
     * The first SAY.TEXT message is the user's task (stored at session start).
     * Subsequent SAY.TEXT messages are agent responses.
     */
    private fun formatSessionAsMarkdown(baseDir: java.io.File, sessionId: String): String? {
        val sessionDir = java.io.File(baseDir, "sessions/$sessionId")
        val messages = MessageStateHandler.loadUiMessages(sessionDir)
        if (messages.isEmpty()) return null

        // Find the session task from the global index
        val items = MessageStateHandler.loadGlobalIndex(baseDir)
        val task = items.find { it.id == sessionId }?.task ?: "Untitled Session"

        val sb = StringBuilder()
        sb.appendLine("# $task")
        sb.appendLine()

        var firstSayText = true
        for (msg in messages) {
            val text = msg.text?.takeIf { it.isNotBlank() } ?: continue
            when (msg.type) {
                com.workflow.orchestrator.agent.session.UiMessageType.SAY -> {
                    when (msg.say) {
                        com.workflow.orchestrator.agent.session.UiSay.TEXT -> {
                            if (firstSayText) {
                                // First SAY.TEXT is the user's original task
                                sb.appendLine("**User:** $text")
                                sb.appendLine()
                                firstSayText = false
                            } else {
                                sb.appendLine("**Agent:** $text")
                                sb.appendLine()
                            }
                        }
                        else -> {} // skip tool calls, status, etc.
                    }
                }
                com.workflow.orchestrator.agent.session.UiMessageType.ASK -> {
                    when (msg.ask) {
                        com.workflow.orchestrator.agent.session.UiAsk.FOLLOWUP -> {
                            sb.appendLine("**User:** $text")
                            sb.appendLine()
                        }
                        com.workflow.orchestrator.agent.session.UiAsk.COMPLETION_RESULT -> {
                            sb.appendLine("**Agent (completion):** $text")
                            sb.appendLine()
                        }
                        else -> {}
                    }
                }
            }
        }

        return sb.toString().trimEnd()
    }

    /**
     * Resume a previous session.
     *
     * Port of Cline's task resumption flow:
     * - Cline's webview sends "resumeTask" with taskId
     * - ClineProvider loads HistoryItem + apiConversationHistory from disk
     * - Creates new Task instance with restored state
     * - Task picks up execution
     *
     * We replicate this: load session + messages from MessageStateHandler,
     * rebuild ContextManager, and re-enter the agent loop.
     */
    /**
     * Show a previous session read-only (view conversation without starting execution).
     *
     * Loads the UI messages and pushes them to the webview so the user can review
     * the conversation. If the session is not completed, a "Resume" bar is shown
     * at the bottom so the user can explicitly choose to continue execution.
     */
    fun showSession(sessionId: String) {
        LOG.info("AgentController.showSession: $sessionId (view-only)")

        // ── EDT phase 1: dialog decisions + cheap state flips BEFORE the expensive load ──
        // killBackgroundsOnTransition may show a modal confirmation, which must stay on EDT
        // and must resolve before we commit to switching sessions.
        val leaving = currentSessionId
        if (leaving != null && leaving != sessionId) {
            if (!killBackgroundsOnTransition(leaving, "Switching sessions")) return
        }

        // Cancel any running task first
        if (currentJob?.isActive == true) {
            service.cancelCurrentTask()
        }
        walkthroughService()?.endTour(byUser = false)
        currentJob = null
        viewedSessionId = sessionId
        // W4-B2 review Important #1: capture the generation on EDT phase 1; compared at
        // push time alongside the sessionId check (showHistory/resetForNewChat bump it).
        val generation = showSessionGeneration.incrementAndGet()

        val basePath = project.basePath ?: System.getProperty("user.home")

        // ── IO phase: disk read + JSON decode + re-encode of a potentially multi-MB
        // ui_messages.json. P0-7: this ran synchronously in the EDT bridge handler and
        // froze the UI on every History click. Mirrors showHistory(): load + serialize on
        // Dispatchers.IO, push on EDT via invokeLater.
        controllerScope.launch(Dispatchers.IO + CoroutineName("AgentController.showSession")) {
            val sessionDir = java.io.File(ProjectIdentifier.agentDir(basePath), "sessions/$sessionId")
            if (!sessionDir.exists()) {
                LOG.warn("AgentController.showSession: session dir not found for $sessionId")
                return@launch
            }

            // Load UI messages from disk
            val savedUiMessages = MessageStateHandler.loadUiMessages(sessionDir)
            if (savedUiMessages.isEmpty()) {
                LOG.warn("AgentController.showSession: no ui messages for $sessionId")
                return@launch
            }

            // Trim trailing resume markers
            val trimmed = ResumeHelper.trimResumeMessages(savedUiMessages)

            // Determine if this session was already completed
            val isCompleted = ResumeHelper.determineResumeAskType(trimmed) == UiAsk.RESUME_COMPLETED_TASK

            // Serialize off-EDT too — the encode is as heavy as the load for big sessions.
            val (messagesJson, tasksJson) = encodeStateForWebview(trimmed)

            // ── EDT phase 2: push to the webview ──
            invokeLater {
                // Stale-click guard: the user may have clicked another session (or started a
                // new chat) while this one was loading — drop the late push instead of
                // stomping the newer view. The generation check additionally catches
                // navigations that DON'T change viewedSessionId (history view, new chat).
                if (viewedSessionId != sessionId || generation != showSessionGeneration.get()) return@invokeLater

                // Push messages to webview (switches to chat view and shows conversation)
                dashboard.reset()
                pushStateToWebview(messagesJson, tasksJson)
                dashboard.showChatView()
                dashboard.setBusy(false)
                dashboard.setInputLocked(false)

                // Show the resume bar if the session is resumable (not completed)
                if (!isCompleted) {
                    dashboard.showResumeBar(sessionId)
                }

                refreshDelegationQuestionBanner(sessionId)
            }
        }
    }

    /**
     * M2 fix: re-push delegation-question-pending banner state when the user navigates
     * to a delegated session. The banner state is normally pushed in
     * DelegationInboundService.routeQuestion / deliverAnswer, but if the question was
     * raised while the user was viewing a different session the push was silently skipped
     * (pushDelegationQuestionPending gates on viewedSessionId == sessionId, which was
     * not true at question-raise time). We re-check here after viewedSessionId is set.
     */
    private fun refreshDelegationQuestionBanner(sessionId: String) {
        try {
            val inboundService = project.getService(
                com.workflow.orchestrator.agent.delegation.DelegationInboundService::class.java
            )
            if (inboundService.hasPendingQuestion(sessionId)) {
                val delegatorRepo = service.findDelegationMetadata(sessionId)?.delegatorRepo
                pushDelegationQuestionPending(sessionId, active = true, delegatorRepo = delegatorRepo)
            } else {
                // Clear any stale banner state from a prior session that happened to be viewed.
                pushDelegationQuestionPending(sessionId, active = false, delegatorRepo = null)
            }
        } catch (e: Exception) {
            LOG.warn("showSession: delegation banner refresh failed for $sessionId", e)
        }
    }

    /**
     * Resume a session that the user is currently viewing. Called when the user
     * clicks "Resume" in the resume bar, optionally with a message to add.
     */
    fun resumeViewedSession(userText: String? = null) {
        val sessionId = viewedSessionId
        if (sessionId == null) {
            LOG.warn("AgentController.resumeViewedSession: no viewed session to resume")
            return
        }
        viewedSessionId = null
        dashboard.hideResumeBar()
        resumeSession(sessionId, userText)
    }

    fun resumeSession(sessionId: String, userText: String? = null) {
        LOG.info("AgentController.resumeSession: $sessionId")
        viewedSessionId = null
        // Track the resumed session as current so any subsequent user message
        // is routed back into this session (instead of falling through to the
        // new-session path in executeTask, which would wipe the chat view).
        // AgentService.resumeSession has no onSessionStarted callback — we
        // know the id directly, so set it here.
        currentSessionId = sessionId
        sessionActive = true
        prepareForReplay("Resuming session...")

        // Create a fresh input channel for the resumed loop
        userInputChannel = Channel(Channel.RENDEZVOUS)
        loopWaitingForInput = false
        pendingUiMessageOverride.set(null)
        pendingReplyImageRefs.set(emptyList())
        taskStartTime = System.currentTimeMillis()

        val debugEnabled = AgentSettings.getInstance(project).state.showDebugLog

        // Attempt resume — AgentService rebuilds the ContextManager from persisted history.
        // Pass ALL interactive callbacks so the resumed session has full functionality
        // (approvals, plans, steering, token display, etc.).
        val job = service.resumeSession(
            sessionId = sessionId,
            userText = userText,
            onStreamChunk = ::onStreamChunk,
            onToolCall = ::onToolCall,
            onComplete = { result ->
                if (debugEnabled) {
                    val status = when (result) {
                        is LoopResult.Completed -> "completed"
                        is LoopResult.Cancelled -> "cancelled"
                        is LoopResult.Failed -> "failed"
                        is LoopResult.SessionHandoff -> "handoff"
                    }
                    dashboard.pushDebugLogEntry("session", "task_end", status, null)
                }
                onComplete(result)
            },
            onUiMessagesLoaded = { uiMessages -> postStateToWebview(uiMessages) },
            onRetry = { attempt, maxAttempts, reason, delayMs ->
                invokeLater {
                    val delaySec = delayMs / 1000
                    dashboard.appendStatus(
                        "$reason — retrying ($attempt/$maxAttempts) in ${delaySec}s...",
                        RichStreamingPanel.StatusType.WARNING
                    )
                }
            },
            onCompactionState = { active, phase ->
                // Bug 5 — surface auto-compaction to the webview so input locks +
                // overlay shows during the LLM-summary round-trip.
                invokeLater {
                    dashboard.setCompactionState(active, phase)
                    // When compaction finishes the context shrank — refresh the usage bar at once
                    // instead of waiting for its 1s poll (which may be paused via document.hidden).
                    if (!active) dashboard.refreshContextUsage()
                }
            },
            onModelSwitch = { _, to, reason ->
                invokeLater {
                    val cached = com.workflow.orchestrator.core.ai.ModelCache.getCached()
                    // Opportunistic dropdown recovery: a fallback firing means a successful API
                    // call also just landed on the new model, so the network is reachable.
                    // If the dropdown is still empty (initial fetch failed at startup), retry now.
                    if (cached.isEmpty()) loadModelList(force = true)
                    val displayName = cached.find { it.id == to }?.displayName
                        ?: com.workflow.orchestrator.core.ai.dto.ModelInfo.formatModelName(to.substringAfterLast("::"))
                    dashboard.setModelName(displayName)
                    when {
                        reason.startsWith("Escalating back", ignoreCase = true) ->
                            dashboard.setModelFallbackState(false, null)
                        else ->
                            dashboard.setModelFallbackState(true, "$reason — now using $displayName")
                    }
                }
            },
            onPlanResponse = { text, explore, append -> onPlanResponse(text, explore, append) },
            onPlanPartialContent = ::onPlanPartialContent,
            onPlanModeToggled = { enabled -> invokeLater { togglePlanMode(enabled) } },
            onPlanDiscarded = { invokeLater { onPlanDiscardedByLlm() } },
            userInputChannel = userInputChannel,
            pendingChannelImageRefs = { pendingReplyImageRefs.getAndSet(emptyList()) },
            approvalGate = ::approvalGate,
            onSubagentProgress = ::onSubagentProgress,
            onTokenUpdate = ::onTokenUpdate,
            onSessionStats = ::onSessionStats,
            onDebugLog = if (debugEnabled) { level, event, detail, meta ->
                dashboard.pushDebugLogEntry(level, event, detail, meta)
            } else null,
            onSessionStarted = { sid -> currentSessionId = sid },
            onSteeringDrained = { drainedIds ->
                invokeLater { handleSteeringDrained(drainedIds) }
            },
            sessionApprovalStore = sessionApprovalStore,
            onAwaitingUserInput = ::onLoopAwaitingUserInput,
            onUserInputReceived = { _ ->
                // Consumed and cleared atomically — same contract as the executeTask path.
                pendingUiMessageOverride.getAndSet(null)
            },
            streamingEditCallback = streamingEditCallback,
            onContextManagerReady = { cm -> contextManager = cm },
            onHandoffProposed = ::onHandoffProposed,
        )

        if (job != null) {
            currentJob = job
            // Reset contextManager to null transiently; onContextManagerReady (above)
            // repopulates it with the resumed session's live manager once the loop builds
            // it from persisted history, so the next user message appends to the correct context.
            contextManager = null
            // Start working indicator
            startPhraseTimer("Resumed session")
        } else {
            // Resume failed — notify user
            failReplay("Could not resume session. The session may have been deleted or has no saved messages.")
        }
    }

    /**
     * Cancel the current task and reset the dashboard UI before replaying a saved
     * session. Shows the supplied status message.
     */
    private fun prepareForReplay(statusMessage: String) {
        if (currentJob?.isActive == true) {
            service.cancelCurrentTask()
        }
        currentJob = null
        dashboard.reset()
        dashboard.setBusy(true)
        dashboard.setInputLocked(true)
        taskStartTime = System.currentTimeMillis()
        dashboard.appendStatus(statusMessage, RichStreamingPanel.StatusType.INFO)
    }

    /** Show an error and unlock the input bar after a failed replay attempt. */
    private fun failReplay(message: String) {
        dashboard.appendError(message)
        dashboard.setBusy(false)
        dashboard.setInputLocked(false)
        dashboard.focusInput()
    }

    /**
     * Serialize the full UI messages array and push it to the webview for session rehydration.
     *
     * Called during session resume so the chat UI displays the complete conversation history
     * from the previous session. The bridge function `_loadSessionState` (registered in
     * jcef-bridge.ts, Task 9) replaces the chatStore messages with the deserialized array.
     *
     * Also pushes the current TaskStore snapshot via `_setTasks` so the React
     * PlanProgressWidget rehydrates its task list at the same time. If no session is
     * active (task store unavailable) an empty array is sent to clear any stale UI state.
     */
    fun postStateToWebview(uiMessages: List<UiMessage>) {
        val (messagesJson, tasksJson) = encodeStateForWebview(uiMessages)
        pushStateToWebview(messagesJson, tasksJson)
    }

    /**
     * Serialize the session-state payloads for [pushStateToWebview]. Thread-agnostic and
     * potentially expensive (multi-MB JSON encode for long sessions) — callers on a hot
     * path (P0-7: [showSession]) run this on Dispatchers.IO and push the result on EDT.
     */
    private fun encodeStateForWebview(uiMessages: List<UiMessage>): Pair<String, String> {
        val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
        val messagesJson = json.encodeToString(uiMessages)
        val tasks = service.currentTaskStore()?.listTasks().orEmpty()
        val tasksJson = taskEventJson.encodeToString(tasks)
        return messagesJson to tasksJson
    }

    /** Push pre-serialized session state to the webview. Cheap; call from EDT. */
    private fun pushStateToWebview(messagesJson: String, tasksJson: String) {
        dashboard.loadSessionState(messagesJson)
        dashboard.setTasks(tasksJson)
    }

    /**
     * Time-travel revert flow. Suspend so the EDT-launched coroutine can park
     * across I/O without freezing the event thread.
     *
     * Sequence:
     *   1. prepareForReplay → cancel current job
     *   2. job.join() with bounded timeout (I4 race-mitigation: ensures the
     *      in-flight tool's coroutine has unwound before we touch its files)
     *   3. service.revertToUserMessage (I/O dispatcher)
     *   4. Push truncated UI + aggregate diff back to webview
     *   5. Restore user-typed text to chat input — time-travel UX
     *   6. Unlock input
     */
    private suspend fun revertToUserMessage(sessionId: String, messageTs: Long) {
        LOG.info("AgentController.revertToUserMessage: session=$sessionId ts=$messageTs")
        currentSessionId = sessionId
        sessionActive = true

        // Snapshot the job reference before prepareForReplay nulls it.
        val inflightJob = currentJob
        prepareForReplay("Reverting to checkpoint...")

        // I4 race-mitigation: bounded wait for the cancelled job to unwind before
        // we read+restore files it may still be writing to. 500ms upper bound so
        // we never hang the revert if cancellation propagation gets stuck (e.g.
        // WriteCommandAction holding a write lock).
        inflightJob?.let { job ->
            kotlinx.coroutines.withTimeoutOrNull(500L) { job.join() }
        }

        contextManager = null

        val result = withContext(Dispatchers.IO) { service.revertToUserMessage(sessionId, messageTs) }
        val uiMessages = withContext(Dispatchers.IO) { service.loadUiMessages(sessionId) }

        // postStateToWebview, restoreInputText, etc. are EDT-safe — we're on EDT here.
        postStateToWebview(uiMessages)
        dashboard.restoreInputText(result.userText)
        pushAggregateDiff(sessionId)

        dashboard.setBusy(false)
        dashboard.setInputLocked(false)
        dashboard.focusInput()
    }

    /** Push the latest aggregate diff to the webview's bottom bar. Cheap to call. */
    private suspend fun pushAggregateDiff(sessionId: String) {
        val agg = withContext(Dispatchers.IO) { service.getAggregateDiff(sessionId) }
        val aggJson = taskEventJson.encodeToString(agg)
        dashboard.updateAggregateDiff(aggJson)
    }

    // ═══════════════════════════════════════════════════
    //  Plan mode lifecycle — continuous conversation (Cline pattern)
    // ═══════════════════════════════════════════════════

    /**
     * Callback from AgentLoop when plan_mode_respond is called.
     * Renders the plan card in the UI. The loop does NOT exit.
     *
     * If needsMoreExploration=true, the loop continues immediately.
     * If needsMoreExploration=false, the loop will wait on userInputChannel
     * for the user to respond (type chat, add comments, or approve).
     */

    /**
     * Called when a plan_mode_respond was truncated mid-emission (finish_reason=length).
     * The content that WAS emitted inside <response> is pre-populated into [accumulatedPlanText]
     * so that when the LLM calls plan_mode_respond again with append=true, [onPlanResponse]
     * can stitch together prefix + continuation into a complete plan.
     *
     * Called on the IO coroutine (AgentLoop.run) before the loop iterates — guaranteed to
     * execute before the next onPlanResponse invocation.
     */
    private fun onPlanPartialContent(partialContent: String) {
        accumulatedPlanText = if (accumulatedPlanText.isNotBlank()) {
            "$accumulatedPlanText\n$partialContent"
        } else {
            partialContent
        }
        LOG.info("AgentController: pre-populated plan accumulator with ${partialContent.length} chars from truncated call")
    }

    private fun onPlanResponse(planText: String, needsMoreExploration: Boolean, append: Boolean = false) {
        val fullPlan = if (append && accumulatedPlanText.isNotBlank()) {
            "$accumulatedPlanText\n$planText"
        } else {
            planText
        }
        accumulatedPlanText = fullPlan

        // Save plan to disk and store path in ContextManager for compaction survival.
        // Done outside invokeLater so it's synchronous and guaranteed before UI render.
        val sid = currentSessionId
        if (sid != null) {
            try {
                val basePath = project.basePath ?: System.getProperty("user.home")
                val sessionDir = java.io.File(
                    ProjectIdentifier.agentDir(basePath),
                    "sessions/$sid"
                )
                val planFile = java.io.File(sessionDir, "plan.md")
                planFile.parentFile?.mkdirs()
                planFile.writeText(fullPlan, Charsets.UTF_8)
                // Store path in ContextManager so it survives compaction
                contextManager?.setActivePlanPath(planFile.absolutePath)
                LOG.info("AgentController: plan saved to ${planFile.absolutePath}")
            } catch (e: Exception) {
                LOG.warn("AgentController: failed to save plan to disk: ${e.message}")
            }
        } else {
            LOG.warn("AgentController: onPlanResponse called but currentSessionId is null — plan not saved")
        }

        invokeLater {
            val summary = fullPlan.lines()
                .firstOrNull { it.isNotBlank() && !it.startsWith("#") }
                ?.trim()?.take(300)
                ?: "Implementation plan"
            val planData = PlanJson(summary = summary, markdown = fullPlan)
            currentPlanData = planData
            val planJson = Json.encodeToString(planData)
            dashboard.renderPlan(planJson)

            if (!needsMoreExploration) {
                // The loop is now waiting for user input — unlock the UI
                loopWaitingForInput = true
                dashboard.setBusy(false)
                // Input is never locked — user always types freely
                dashboard.focusInput()
            }
        }
    }

    /**
     * Callback from AgentLoop when new_task proposes a handoff. Renders the preview card
     * and marks the loop as waiting — the user's button click feeds a sentinel into
     * [userInputChannel] (see [startFreshSession] / [keepChatting]). Mirrors [onPlanResponse].
     */
    private fun onHandoffProposed(context: String) {
        LOG.info("AgentController.onHandoffProposed (${context.length} chars)")
        val json = taskEventJson.encodeToString(HandoffJson(summary = context))
        invokeLater {
            dashboard.renderHandoff(json)
            loopWaitingForInput = true
            dashboard.setBusy(false)
            dashboard.focusInput()
        }
    }

    /** User clicked "Start fresh session" on the handoff card — fork via the loop sentinel. */
    private fun startFreshSession() {
        LOG.info("AgentController.startFreshSession (handoff fork)")
        invokeLater { dashboard.clearHandoffInUi() }
        val channel = userInputChannel
        if (loopWaitingForInput && channel != null && currentJob?.isActive == true) {
            loopWaitingForInput = false
            dashboard.setBusy(true)
            controllerScope.launch(Dispatchers.EDT + CoroutineName("AgentController.startFreshSession.send")) {
                channel.send(com.workflow.orchestrator.agent.loop.AgentLoop.HANDOFF_FORK_SENTINEL)
            }
        } else {
            LOG.warn("AgentController.startFreshSession: no suspended loop to receive the decision")
        }
    }

    /** User clicked "Keep chatting here" — decline the handoff, stay in the current session. */
    private fun keepChatting() {
        LOG.info("AgentController.keepChatting (handoff declined)")
        invokeLater { dashboard.clearHandoffInUi() }
        val channel = userInputChannel
        if (loopWaitingForInput && channel != null && currentJob?.isActive == true) {
            loopWaitingForInput = false
            // Only confirm the choice once we know the decision will actually reach the loop.
            invokeLater { dashboard.appendStatus("Staying in this session.", RichStreamingPanel.StatusType.INFO) }
            controllerScope.launch(Dispatchers.EDT + CoroutineName("AgentController.keepChatting.send")) {
                channel.send(com.workflow.orchestrator.agent.loop.AgentLoop.HANDOFF_DECLINE_SENTINEL)
            }
        } else {
            LOG.warn("AgentController.keepChatting: no suspended loop to receive the decision")
        }
    }

    /**
     * User clicked approve — show a programmatic question asking whether to clear
     * the research context before execution. The actual mode switch and approval
     * instruction happen in [handleApprovalChoice] after the user picks an option.
     */
    private fun approvePlan() {
        LOG.info("AgentController.approvePlan — showing context clearing choice")
        pendingApprovalChoice = true

        val questionsJson = """
        {
            "questions": [{
                "id": "approval_mode",
                "question": "How would you like to proceed?",
                "type": "single",
                "options": [
                    {
                        "id": "clear_context",
                        "label": "Approve & Clear Context (recommended)",
                        "description": "Clears research history to free context budget. Keeps plan, active skill, facts, and guardrails."
                    },
                    {
                        "id": "keep_context",
                        "label": "Just Approve",
                        "description": "Keeps the full conversation context from the planning phase."
                    }
                ]
            }]
        }
        """.trimIndent()

        invokeLater { dashboard.showQuestions(questionsJson) }
    }

    /**
     * Handle the user's choice from the programmatic approval question.
     * Clears context if requested, then switches to act mode and feeds the
     * approval instruction into the waiting loop.
     */
    private fun handleApprovalChoice(answers: Map<String, String>) {
        pendingApprovalChoice = false

        // Parse the selected option — answers map: questionId → JSON array of selected option IDs
        val selectedJson = answers["approval_mode"] ?: "[\"keep_context\"]"
        val clearContext = selectedJson.contains("clear_context")

        LOG.info("AgentController.handleApprovalChoice — clearContext=$clearContext")

        if (clearContext) {
            contextManager?.clearMessages()
        }

        // Switch to act mode
        service.setPlanModeActive(false)
        dashboard.setPlanMode(false)

        // Mark the plan as approved in the UI — switches PlanSummaryCard → PlanProgressWidget
        dashboard.approvePlanInUi()

        // Build the approval instruction.
        // When context was cleared, the plan content is no longer in the conversation.
        // Include it inline (with the original task for full intent) so the LLM can
        // proceed without needing read_file on the external plan path (which PathValidator
        // blocks as outside the project).
        //
        // originalTaskText is set by executeTask on first message — but resumeSession()
        // bypasses executeTask, so it is null for resumed sessions. Fall back to the
        // MessageStateHandler.taskText (200-char task summary written at session creation).
        val effectiveOriginalTask = originalTaskText
            ?: service.activeMessageStateHandler?.taskText?.takeIf { it.isNotBlank() }
        val instruction = buildString {
            appendLine("The user has approved the plan.")
            if (clearContext) {
                effectiveOriginalTask?.let {
                    appendLine()
                    appendLine("<original_task>")
                    appendLine(it)
                    appendLine("</original_task>")
                }
                val planMarkdown = currentPlanData?.markdown
                if (!planMarkdown.isNullOrBlank()) {
                    appendLine()
                    appendLine("<approved_plan>")
                    appendLine(planMarkdown)
                    appendLine("</approved_plan>")
                }
            }
        }.trim()

        // Build a typed UI message so the persisted bubble shows "Implementation plan approved"
        // (with the plan markdown attached) rather than the raw XML instruction text.
        val uiApprovalMsg = UiMessage(
            type = UiMessageType.SAY,
            say = UiSay.PLAN_APPROVED,
            ts = System.currentTimeMillis(),
            text = "Implementation plan approved",
            planApprovalData = currentPlanData?.markdown?.let { PlanApprovalData(it) }
        )

        // Pass displayText so the live-UI append (AgentController.executeTask wrapper's
        // displayUserMessage call) also shows the clean label instead of raw XML.
        executeTask(
            task = instruction,
            displayText = "Implementation plan approved",
            uiMessageOverride = uiApprovalMsg
        )
    }

    /**
     * User submitted per-line comments on the plan — format and inject into the loop.
     *
     * The loop stays in plan mode. The revision message goes through the
     * [userInputChannel] directly (NOT through [executeTask]) so it does NOT
     * appear as a user-typed message in the chat. A small status indicator
     * is shown instead.
     *
     * @param commentsJson v2 JSON: `{"comments":[{line,content,comment}],"markdown":"..."}`
     */
    private fun revisePlan(commentsJson: String) {
        LOG.info("AgentController.revisePlan: $commentsJson")

        val revisionMessage = buildRevisionMessage(commentsJson)
        val commentCount = countRevisionComments(commentsJson)

        // Show a status indicator instead of a fake "user" message
        dashboard.appendStatus("Plan revision requested with $commentCount comment(s)")
        dashboard.setBusy(true)

        // Inject directly into the channel — bypass executeTask to avoid
        // displaying the generated prompt as a user message (Bug 1 fix)
        val channel = userInputChannel
        if (loopWaitingForInput && channel != null && currentJob?.isActive == true) {
            loopWaitingForInput = false
            controllerScope.launch(Dispatchers.EDT + CoroutineName("AgentController.revisePlan.send")) {
                channel.send(revisionMessage)
            }
        } else if (currentJob?.isActive == true) {
            // Loop is running but not waiting — queue as steering
            val steeringId = "steer-revise-${System.currentTimeMillis()}"
            activeSessionQueue()?.enqueue(
                QueuedMessage(
                    id = steeringId,
                    kind = QueueSourceKind.USER,
                    body = revisionMessage,
                    timestamp = System.currentTimeMillis(),
                    priority = UserQueuePolicy.priority,
                ),
            )
        } else {
            LOG.warn("AgentController.revisePlan: no active loop to receive revision")
            dashboard.setBusy(false)
        }
    }

    /**
     * Shared discard logic used by both LLM-initiated (via discard_plan tool)
     * and user-initiated (via Dismiss button) plan discard flows.
     *
     * @param userInitiated If true, also injects a user-role conversation marker
     *   so the LLM understands the user chose to dismiss the plan.
     */
    private fun performPlanDiscard(userInitiated: Boolean) {
        LOG.info("AgentController.performPlanDiscard(userInitiated=$userInitiated)")
        currentPlanData = null
        accumulatedPlanText = ""
        invokeLater { dashboard.clearPlanInUi() }

        if (userInitiated) {
            val marker = "[User dismissed the pending plan. Continue the conversation in plan mode — do not re-present the dismissed plan unless explicitly asked.]"
            // Route through channel if loop is suspended waiting for input,
            // otherwise through steering queue (mirrors revisePlan pattern).
            val channel = userInputChannel
            if (loopWaitingForInput && channel != null && currentJob?.isActive == true) {
                loopWaitingForInput = false
                controllerScope.launch(Dispatchers.EDT + CoroutineName("AgentController.performPlanDiscard.send")) {
                    channel.send(marker)
                }
            } else if (currentJob?.isActive == true) {
                val steeringId = "steer-dismiss-${System.currentTimeMillis()}"
                activeSessionQueue()?.enqueue(
                    QueuedMessage(
                        id = steeringId,
                        kind = QueueSourceKind.USER,
                        body = marker,
                        timestamp = System.currentTimeMillis(),
                        priority = UserQueuePolicy.priority,
                    ),
                )
            }
        }
    }

    /** User clicked Dismiss on the plan card. */
    private fun dismissPlan() {
        LOG.info("AgentController.dismissPlan — user-initiated plan dismissal")
        // Sequence the history rewrite and the plan-discard dispatch inside one coroutine so
        // the rewrite lands before performPlanDiscard's channel.send fires. JBCefJSQuery
        // handlers run on EDT, so the previous synchronous blocking call froze EDT — the
        // earlier comment claiming "JCEF thread, not EDT" was wrong.
        controllerScope.launch(Dispatchers.EDT + CoroutineName("AgentController.dismissPlan")) {
            withContext(Dispatchers.IO) {
                service.activeMessageStateHandler
                    ?.rewriteMostRecentToolResult("plan_mode_respond", "[Plan discarded — do not reference]")
            }
            performPlanDiscard(userInitiated = true)
        }
    }

    /** Called when LLM uses the discard_plan tool. */
    internal fun onPlanDiscardedByLlm() {
        LOG.info("AgentController.onPlanDiscardedByLlm — LLM-initiated plan dismissal")
        // History rewrite already done inside AgentService executeTask closure
        performPlanDiscard(userInitiated = false)
    }

    private fun togglePlanMode(enabled: Boolean) {
        LOG.info("AgentController.togglePlanMode: $enabled")
        service.setPlanModeActive(enabled)
        dashboard.setPlanMode(enabled)
    }

    /**
     * The model id that DISPLAY reads (bar, picker row, TopBar) should be keyed on.
     * This is the saved/selected model — the one the user is looking at in the picker.
     * Falls back to the running brain model when the selection is blank (auto-pick path).
     */
    private fun selectedModelId(): String? =
        AgentSettings.getInstance(project).state.sourcegraphChatModel
            ?.takeIf { it.isNotBlank() }
            ?: service.getCurrentBrainModelId()

    /**
     * DISPLAY max-input-tokens for the bar + TopBar — the override-aware window keyed on the
     * SELECTED model (so both surfaces stay identical to the selected picker row). The picker
     * ROW intentionally keys on its own `m.id`, not this, and stays inline.
     */
    private fun displayMaxInputTokens(): Int =
        service.getEffectiveContextWindow().maxInputTokens(selectedModelId())

    /**
     * Called by [com.workflow.orchestrator.agent.settings.AgentAdvancedConfigurable]
     * (via [AgentControllerRegistry.getController]) after the user applies new per-model or
     * global context-window overrides in Settings.  Re-emits the model list so each row's
     * capacity strip updates immediately, then fires a context-usage refresh so the bar
     * below the input updates in the same Settings.apply() cycle.
     */
    fun notifyContextWindowOverridesChanged() {
        loadModelList(force = true)       // re-emit updateModelList with new effective per-row windows
        dashboard.refreshContextUsage()   // fire the usage-refresh event so the bar updates at once
    }

    private fun changeModel(model: String) {
        LOG.info("AgentController.changeModel: $model")
        val settings = AgentSettings.getInstance(project)
        settings.state.sourcegraphChatModel = model
        // Resolve formatted display name from cache, fall back to formatModelName on the raw ID
        val cached = com.workflow.orchestrator.core.ai.ModelCache.getCached()
        val displayName = cached.find { it.id == model }?.displayName
            ?: com.workflow.orchestrator.core.ai.dto.ModelInfo.formatModelName(model.substringAfterLast("::"))
        dashboard.setModelName(displayName)
        // Bug 3 — request the AgentLoop to swap brains at the next iteration boundary.
        // Without this, settings.sourcegraphChatModel was a write-only field after session
        // start; the live brain was never recycled and follow-up messages kept using the
        // session's original model (or a previous fallback model that locked the chain).
        service.requestModelChange(model)
        // Clear any "fallback active" UI badge — the user has taken authoritative control.
        dashboard.setModelFallbackState(false, null)
    }

    private fun openSettings() {
        ShowSettingsUtil.getInstance().showSettingsDialog(
            project,
            "Workflow Orchestrator"
        )
    }

    private fun showToolsPanel() {
        val toolsJson = buildToolsJson()
        dashboard.showToolsPanel(toolsJson)
    }

    // ═══════════════════════════════════════════════════
    //  Mirror panel support (editor tab)
    // ═══════════════════════════════════════════════════

    /**
     * Register a mirror dashboard panel (e.g. "View in Editor" tab).
     * The mirror receives all output calls from the primary dashboard.
     */
    fun addMirrorPanel(mirror: AgentDashboardPanel) {
        dashboard.addMirror(mirror)
        // Wire the mirror's input callbacks identically to the primary dashboard
        wireSharedDashboardCallbacks(mirror)
        // Reuse the shared provider so mirrors benefit from the ticket context cache
        sharedMentionSearchProvider?.let { mirror.setMentionSearchProvider(it) }
    }

    fun removeMirrorPanel(mirror: AgentDashboardPanel) {
        dashboard.removeMirror(mirror)
    }

    // ═══════════════════════════════════════════════════
    //  Navigation helpers
    // ═══════════════════════════════════════════════════

    private val pathLinkResolver: PathLinkResolver by lazy { PathLinkResolver(project) }

    private fun navigateToFile(path: String, line: Int) {
        val input = if (line > 0) "$path:$line" else path
        val resolved = pathLinkResolver.resolveForOpen(input) ?: run {
            LOG.info("AgentController: rejected navigateToFile target '$path'")
            return
        }
        invokeLater {
            try {
                val vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                    .findFileByPath(resolved.canonicalPath) ?: return@invokeLater
                val descriptor = com.intellij.openapi.fileEditor.OpenFileDescriptor(
                    project, vf, resolved.line, resolved.column
                )
                FileEditorManager.getInstance(project).openEditor(descriptor, true)
            } catch (e: Exception) {
                LOG.warn("AgentController: failed to open ${resolved.canonicalPath}", e)
            }
        }
    }

    private fun handleValidatePaths(pathsJson: String, callbackName: String) {
        controllerScope.launch(Dispatchers.IO) {
            val resultJson = try {
                val paths = Json.decodeFromString<List<String>>(pathsJson)
                val validated = pathLinkResolver.validate(paths).map {
                    ValidatedPathJson(it.input, it.canonicalPath, it.line, it.column)
                }
                Json.encodeToString(validated)
            } catch (e: Exception) {
                LOG.warn("handleValidatePaths: bad payload", e)
                "[]"
            }
            // Push result to the one-shot JS callback (same pattern as validateTicket).
            val cbJson = Json.encodeToString(callbackName)
            val resJson = Json.encodeToString(resultJson)
            invokeLater { dashboard.callJs("(window[$cbJson])($resJson)") }
        }
    }

    private fun handleResolveSymbols(hrefsJson: String, callbackName: String) {
        controllerScope.launch(Dispatchers.IO) {
            val resultJson = try {
                val hrefs = Json.decodeFromString<List<String>>(hrefsJson)
                val resolver = com.workflow.orchestrator.agent.link.SymbolLinkResolver(
                    project,
                    service.providerRegistry,
                )
                val validated = resolver.resolveAll(hrefs).map {
                    ValidatedPathJson(it.input, it.canonicalPath, it.line, it.column)
                }
                Json.encodeToString(validated)
            } catch (e: Exception) {
                LOG.warn("handleResolveSymbols: bad payload", e)
                "[]"
            }
            val cbJson = Json.encodeToString(callbackName)
            val resJson = Json.encodeToString(resultJson)
            invokeLater { dashboard.callJs("(window[$cbJson])($resJson)") }
        }
    }

    private fun openChatInEditorTab() {
        invokeLater {
            val chatFile = AgentChatVirtualFile(project)
            FileEditorManager.getInstance(project).openFile(chatFile, true)
        }
    }

    /**
     * Open the current plan in a full JCEF editor tab (AgentPlanEditor).
     * Wires approve/revise/comment-count callbacks so the editor tab stays in sync
     * with the chat panel's plan card.
     *
     * Called when the user clicks "View Plan" in the chat plan card.
     */
    private fun openPlanInEditor() {
        val plan = currentPlanData ?: return
        val sid = currentSessionId ?: "unknown"
        val vf = AgentPlanVirtualFile(plan, sid)

        invokeLater {
            val editors = FileEditorManager.getInstance(project).openFile(vf, true)
            val planEditor = editors.filterIsInstance<AgentPlanEditor>().firstOrNull()
            if (planEditor != null) {
                planEditor.onApprove = ::approvePlan
                planEditor.onRevise = ::revisePlan
                planEditor.onCommentCountChanged = { count ->
                    dashboard.setPlanCommentCount(count)
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════

    private fun buildToolsJson(): String =
        service.registry.allTools().joinToString(",", "[", "]") { tool ->
            val escapedName = tool.name.replace("\"", "\\\"")
            val escapedDesc = tool.description.take(200).replace("\"", "\\\"").replace("\n", " ")
            """{"name":"$escapedName","description":"$escapedDesc","enabled":true}"""
        }

    /**
     * Multimodal-agent Phase 6 (F-P5-3) — name-based heuristic for vision
     * capability. Used by [loadModelList] to populate the `vision` field on
     * each entry of the model-list payload pushed to the JS chat input.
     *
     * Source: Sourcegraph capabilities baseline (`reference_sourcegraph_image_transport.md`)
     * — these model families pass the 24/24 vision probe:
     *   - Anthropic Claude 4 / 4.5 (Sonnet, Opus, Haiku) and 3.5 / 3.7 family
     *   - OpenAI GPT-4o, GPT-4 Vision, GPT-4 Turbo
     *   - Google Gemini 1.5 / 2.0 / 2.5 Pro and Flash
     *
     * Conservative bias: returns false for unknown/legacy models (e.g.
     * Anthropic Claude 2, OpenAI GPT-3.5, etc.) so users get a Send-time toast
     * rather than a confusing "image silently dropped" reply.
     *
     * Will be replaced with `ModelCatalogService.supportsVision()` once that
     * service is wired live into this controller (deferred from Phase 6).
     */
    internal fun isLikelyVisionCapable(modelId: String): Boolean {
        val lower = modelId.lowercase()
        return when {
            // Anthropic Claude 4/4.5 family
            lower.contains("claude-opus-4") -> true
            lower.contains("claude-sonnet-4") -> true
            lower.contains("claude-haiku-4") -> true
            // Anthropic Claude 3.5 / 3.7 family — all vision-capable
            lower.contains("claude-3-5") || lower.contains("claude-3.5") -> true
            lower.contains("claude-3-7") || lower.contains("claude-3.7") -> true
            // OpenAI GPT-4 vision-capable variants
            lower.contains("gpt-4o") -> true
            lower.contains("gpt-4-vision") -> true
            lower.contains("gpt-4-turbo") -> true
            lower.contains("gpt-4.5") -> true
            lower.contains("gpt-5") -> true
            // Google Gemini 1.5+ Pro/Flash
            lower.contains("gemini-1.5") || lower.contains("gemini-1-5") -> true
            lower.contains("gemini-2") -> true
            else -> false
        }
    }

    /**
     * Format token count for display: "45K" for large counts, exact for small.
     * Ported from Cline's webview token display pattern.
     */
    private fun formatTokenCount(tokens: Int): String =
        if (tokens >= 1000) "${tokens / 1000}K" else tokens.toString()

    /**
     * Start a 30-second timer that generates humorous contextual working phrases via Haiku.
     * Fire-and-forget: failures are silently ignored (the current phrase stays).
     * Gated by smartWorkingIndicator setting.
     */
    private fun startPhraseTimer(task: String) {
        phraseTimerJob?.cancel()

        if (!AgentSettings.getInstance(project).state.smartWorkingIndicator) {
            LOG.info("AgentController: smart working indicator disabled, skipping phrase timer")
            return
        }

        LOG.info("AgentController: starting Haiku phrase timer (30s interval)")
        // Bug 9 — track the most recently displayed phrase so we can pass it to Haiku.
        // The new prompt contract uses this to decide whether the situation has shifted
        // enough to warrant a new line, or whether (no change) is the honest answer.
        var lastDisplayed: String? = null
        var lastActivitySig: Int? = null
        phraseTimerJob = controllerScope.launch(Dispatchers.IO) {
            delay(30_000)
            while (isActive) {
                try {
                    val tools = synchronized(recentToolCalls) { recentToolCalls.toList() }
                    val agentThinking = lastStreamSnippet.takeLast(100)
                    val sig = PhraseActivityGate.signature(tools, agentThinking)
                    if (PhraseActivityGate.shouldGenerate(lastActivitySig, sig)) {
                        lastActivitySig = sig
                        LOG.info("AgentController: requesting Haiku phrase (${tools.size} recent tools)")
                        val phrase = HaikuPhraseGenerator.generate(task, tools, agentThinking, lastDisplayed)
                        if (phrase != null) {
                            LOG.info("AgentController: got Haiku phrase: $phrase")
                            lastDisplayed = phrase
                            invokeLater { dashboard.setSmartWorkingPhrase(phrase) }
                        } else {
                            LOG.info("AgentController: Haiku phrase returned null (no-change or failure)")
                        }
                    } else {
                        LOG.info("AgentController: skipping Haiku phrase — no agent activity since last tick (P1-10 gate)")
                    }
                } catch (e: Exception) {
                    LOG.warn("AgentController: Haiku phrase timer error: ${e.message}")
                }
                delay(30_000)
            }
        }
    }

    /**
     * Set the conversation title. On first message this is synchronous — derive
     * a provisional title from the first ~50 chars of the user message so the
     * TopBar shows something meaningful immediately instead of blank.
     *
     * The Haiku re-evaluation happens later in [evaluateTitleOnCompletion],
     * which fires at loop exit with the user message PLUS the assistant response
     * for much better context.
     */
    private fun generateConversationTitle(task: String, isFirstMessage: Boolean) {
        if (!isFirstMessage) return
        if (currentHaikuTitle != null) return

        val provisional = deriveInitialTitle(task)
        currentHaikuTitle = provisional
        currentSessionId?.let { service.updateSessionTitle(it, provisional) }
        invokeLater { dashboard.setSessionTitle(provisional) }
        LOG.info("AgentController: provisional title set from user message: '$provisional'")
    }

    /** Trim the user's first message to a display-friendly title under 50 chars. */
    private fun deriveInitialTitle(task: String): String {
        val cleaned = task.trim().replace(Regex("\\s+"), " ")
        return if (cleaned.length <= 50) cleaned else cleaned.take(49).trimEnd() + "\u2026"
    }

    /**
     * Called at loop exit on successful completion. Hands Haiku the user
     * message, the assistant's final response, and the current title — Haiku
     * decides KEEP or returns a replacement title. The update is pushed with
     * an "animated" flag so the frontend can play the scramble transition.
     *
     * Failure-tolerant: any error leaves the current title untouched.
     */
    private fun evaluateTitleOnCompletion(assistantResponse: String) {
        val currentTitle = currentHaikuTitle ?: return
        val userMessage = lastTaskText ?: return
        if (assistantResponse.isBlank()) return

        controllerScope.launch(Dispatchers.IO) {
            try {
                val newTitle = HaikuPhraseGenerator.evaluateTitleFromCompletion(
                    currentTitle = currentTitle,
                    userMessage = userMessage,
                    assistantResponse = assistantResponse,
                ) ?: return@launch

                currentHaikuTitle = newTitle
                currentSessionId?.let { service.updateSessionTitle(it, newTitle) }
                invokeLater { dashboard.setSessionTitleAnimated(newTitle) }
                LOG.info("AgentController: title replaced via completion eval: '$newTitle'")
            } catch (e: Exception) {
                LOG.debug("AgentController: completion-title eval failed: ${e.message}")
            }
        }
    }

    /**
     * Parse a render-outcome JSON payload from the webview and forward the decoded
     * result to [ArtifactResultRegistry] so the corresponding suspended
     * `render_artifact` tool call resumes.
     *
     * Payload shape (from `ArtifactRenderer` / `sandbox-main.ts`):
     * ```
     * { "renderId": "...", "status": "success" | "error",
     *   "heightPx": <int?>,
     *   "phase": "render" | "transpile" | "runtime" | "init",
     *   "message": "...",
     *   "missingSymbols": ["Foo", "Bar"],
     *   "line": <int?> }
     * ```
     *
     * Malformed payloads are dropped with a warning — never throw here, the JCEF
     * bridge runs on the EDT and a throw would propagate into browser land.
     */
    private fun parseAndDispatchArtifactResult(rawJson: String) {
        try {
            val obj = Json.parseToJsonElement(rawJson).jsonObject
            val renderId = obj["renderId"]?.jsonPrimitive?.content ?: run {
                LOG.warn("artifact result missing renderId: $rawJson")
                return
            }
            val status = obj["status"]?.jsonPrimitive?.content ?: "error"
            val result: ArtifactRenderResult = if (status == "success") {
                val height = obj["heightPx"]?.jsonPrimitive?.content?.toIntOrNull()
                ArtifactRenderResult.Success(heightPx = height)
            } else {
                val phase = obj["phase"]?.jsonPrimitive?.content ?: "runtime"
                val message = obj["message"]?.jsonPrimitive?.content ?: "Unknown render error"
                val missing = obj["missingSymbols"]?.jsonArray
                    ?.mapNotNull { it.jsonPrimitive.content.takeIf { s -> s.isNotBlank() } }
                    ?: emptyList()
                val line = obj["line"]?.jsonPrimitive?.content?.toIntOrNull()
                ArtifactRenderResult.RenderError(
                    phase = phase,
                    message = message,
                    missingSymbols = missing,
                    line = line,
                )
            }
            ArtifactResultRegistry.getInstance(project).reportResult(renderId, result)
        } catch (e: Exception) {
            LOG.warn("failed to parse artifact result JSON: $rawJson", e)
        }
    }

    /**
     * Handle round-trip confirmation from JS interactive renders (questions, plans, approvals).
     *
     * Payload: `{ "type": "question"|"plan"|"approval", "status": "ok"|"error", "message"?: "..." }`
     *
     * On error, the JS side already rendered a fallback (plain text). This handler logs
     * the result so we have diagnostic visibility into silent JCEF failures.
     */
    private fun handleInteractiveRenderResult(rawJson: String) {
        try {
            val obj = Json.parseToJsonElement(rawJson).jsonObject
            val type = obj["type"]?.jsonPrimitive?.content ?: "unknown"
            val status = obj["status"]?.jsonPrimitive?.content ?: "unknown"
            val message = obj["message"]?.jsonPrimitive?.content

            if (status == "ok") {
                LOG.info("Interactive render confirmed: type=$type, status=ok")
                // Signal the watchdog timer in AskQuestionsTool that the UI rendered.
                // This prevents the 10s watchdog from auto-resolving the deferred
                // when the UI is alive but the user just hasn't answered yet.
                if (type == "question") {
                    askQuestionsTool.uiRenderConfirmed = true
                }
            } else {
                LOG.warn("Interactive render FAILED: type=$type, status=$status, message=$message")
                // JS side already handled the fallback rendering.
                // Push a debug log entry so the user can see it in the API debug tab.
                dashboard.pushDebugLogEntry("warn", "render_failed",
                    "Interactive $type render failed: ${message ?: "unknown error"}", null)
            }
        } catch (e: Exception) {
            LOG.warn("Failed to parse interactive render result: $rawJson", e)
        }
    }

    override fun dispose() {
        LOG.info("AgentController.dispose")
        if (currentJob?.isActive == true) {
            service.cancelCurrentTask()
        }
        clearActiveLoopState()
        phraseTimerJob?.cancel()
        phraseTimerJob = null
        contextManager = null
        sessionApprovalStore.clear()
        currentSessionId = null
        currentPlanData = null
        accumulatedPlanText = ""
        pendingApproval?.cancel()
        pendingApproval = null
        pendingApprovalToolName = null
        // Cancel the long-lived controller scope so the TaskChanged / BackgroundChanged
        // EventBus subscriptions stop collecting once the controller is disposed.
        controllerScope.cancel()
        // Drop the artifact push callback so the registry cannot invoke a
        // stale dashboard reference if a render fires in the window between
        // this dispose and a new controller installing its own callback.
        // Matches the "remove listener on tear-down" pattern used elsewhere.
        ArtifactResultRegistry.getInstance(project).setPushCallback(null)
        // Clear the auto-wake listener so a stale controller reference is not
        // held by AgentService after disposal.
        service.setAutoWakeListener(null)
        // Null out this controller in the registry so that repeated tab opens do
        // not accumulate stale AgentController instances (each holding a live
        // controllerScope + JCEF browser). Compare-and-null to avoid clobbering
        // a newer controller that has already registered itself (race-safe).
        // Closes audit finding agent-ui:F-5.
        val reg = AgentControllerRegistry.getInstance(project)
        if (reg.controller === this) reg.controller = null
    }
}

// ── Plan revision helpers (extracted for testability) ──────────────────────────

/**
 * Build a human-readable revision message from the plan editor's v2 JSON payload.
 *
 * v2 format: `{"comments": [{line, content, comment}], "markdown": "..."}`
 *
 * The output is injected into the agent loop as a user message so the LLM
 * can see the per-line comments and revise the plan accordingly.
 */
internal fun buildRevisionMessage(commentsJson: String): String {
    return buildString {
        appendLine("I have comments on your plan. Please revise it:")
        appendLine()
        try {
            val root = kotlinx.serialization.json.Json.parseToJsonElement(commentsJson)
            if (root is kotlinx.serialization.json.JsonObject && root.containsKey("comments")) {
                val comments = root["comments"]!!.jsonArray
                for (item in comments) {
                    val obj = item.jsonObject
                    val line = obj["line"]?.jsonPrimitive?.intOrNull
                    val content = obj["content"]?.jsonPrimitive?.content ?: ""
                    val comment = obj["comment"]?.jsonPrimitive?.content ?: ""
                    if (comment.isNotBlank()) {
                        if (line != null && content.isNotBlank()) {
                            appendLine("- Line $line (`$content`): $comment")
                        } else {
                            appendLine("- $comment")
                        }
                    }
                }
            } else {
                // Unknown format — include raw text as fallback
                appendLine(commentsJson)
            }
        } catch (_: Exception) {
            // Invalid JSON — include raw text so the LLM can still try
            appendLine(commentsJson)
        }
        appendLine()
        appendLine("Please revise the plan and present the updated version using plan_mode_respond.")
    }
}

/**
 * JSON-serializable mirror of [com.workflow.orchestrator.core.util.ValidatedPath].
 * Used by [AgentController.handleValidatePaths] to encode path validation results
 * for the `_validatePaths` JCEF bridge.
 */
@Serializable
private data class ValidatedPathJson(
    val input: String,
    val canonicalPath: String,
    val line: Int,
    val column: Int,
)

/**
 * JSON payload sent to the webview's `renderHandoff` function when new_task proposes
 * a handoff. The webview renders [HandoffPreviewCard] with the summary and the
 * two-button decision UI.
 */
@Serializable
private data class HandoffJson(val summary: String)

/**
 * Count the number of non-blank comments in a v2 revision payload.
 * Returns 0 for invalid or empty payloads.
 */
internal fun countRevisionComments(commentsJson: String): Int {
    return try {
        val root = kotlinx.serialization.json.Json.parseToJsonElement(commentsJson)
        if (root is kotlinx.serialization.json.JsonObject && root.containsKey("comments")) {
            root["comments"]!!.jsonArray.count { item ->
                val comment = item.jsonObject["comment"]?.jsonPrimitive?.content ?: ""
                comment.isNotBlank()
            }
        } else 0
    } catch (_: Exception) {
        0
    }
}

/** A non-image file attachment parsed from the JS bridge payload. */
data class FileAttachment(
    val sha256: String,
    val mime: String,
    val size: Long,
    val originalFilename: String?,
    val path: String,
)

/**
 * Splits the bridge attachments JSON into image refs (existing vision path) and
 * file attachments (read-on-demand). Attachments with no "kind" default to
 * image for backward compatibility with the pre-file payload shape.
 */
fun splitAttachmentsJson(
    attachmentsJson: String?,
): Pair<List<com.workflow.orchestrator.agent.session.ContentBlock.ImageRef>, List<FileAttachment>> {
    if (attachmentsJson.isNullOrBlank()) return emptyList<com.workflow.orchestrator.agent.session.ContentBlock.ImageRef>() to emptyList()
    return try {
        val arr = kotlinx.serialization.json.Json.parseToJsonElement(attachmentsJson).jsonArray
        val images = mutableListOf<com.workflow.orchestrator.agent.session.ContentBlock.ImageRef>()
        val files = mutableListOf<FileAttachment>()
        for (elem in arr) {
            val obj = elem.jsonObject
            val sha = obj["sha256"]?.jsonPrimitive?.content ?: continue
            val mime = obj["mime"]?.jsonPrimitive?.content ?: continue
            val size = obj["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
            val name = obj["originalFilename"]?.jsonPrimitive?.content
            val kind = obj["kind"]?.jsonPrimitive?.content ?: "image"
            if (kind == "file") {
                val path = obj["path"]?.jsonPrimitive?.content ?: continue
                files += FileAttachment(sha, mime, size, name, path)
            } else {
                images += com.workflow.orchestrator.agent.session.ContentBlock.ImageRef(sha, mime, size, name)
            }
        }
        images to files
    } catch (e: Exception) {
        emptyList<com.workflow.orchestrator.agent.session.ContentBlock.ImageRef>() to emptyList()
    }
}

/**
 * Serialize the user's attachments for the chat-bubble echo. Each entry carries a
 * `kind` so the webview can render images as thumbnails and files as chips
 * (AgentMessage branches on `kind`). Used by both the first-message (startSession)
 * and subsequent-message (appendUserMessage) bubble echoes.
 */
fun bubbleAttachmentsJson(
    images: List<com.workflow.orchestrator.agent.session.ContentBlock.ImageRef>,
    files: List<FileAttachment>,
): String = buildString {
    append("[")
    var first = true
    val entry = { sha: String, mime: String, size: Long, name: String?, kind: String ->
        if (!first) append(",")
        first = false
        append("""{"sha256":${JsEscape.toJsonString(sha)},"mime":${JsEscape.toJsonString(mime)},"size":$size,"kind":"$kind"""")
        name?.let { append(""","originalFilename":${JsEscape.toJsonString(it)}""") }
        append("}")
    }
    images.forEach { entry(it.sha256, it.mime, it.size, it.originalFilename, "image") }
    files.forEach { entry(it.sha256, it.mime, it.size, it.originalFilename, "file") }
    append("]")
}

/** Builds the `<attached_files>` marker appended to the user message, or "" if none. */
fun composeFileMarker(files: List<FileAttachment>): String {
    if (files.isEmpty()) return ""
    val lines = files.joinToString("\n") { "- ${it.path}" }
    return "\n\n<attached_files>\n" +
        "The user attached these files. Read them when relevant with read_file " +
        "(text/code/config) or read_document (pdf, office, rtf, odt, epub, html, csv):\n" +
        "$lines\n</attached_files>"
}

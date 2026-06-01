package com.workflow.orchestrator.agent.delegation

import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.AgentService
import com.workflow.orchestrator.agent.delegation.ui.AcceptDelegationDialog
import com.workflow.orchestrator.agent.session.DelegationMetadata
import com.workflow.orchestrator.core.delegation.DelegationMessage
import com.workflow.orchestrator.core.delegation.DelegationPaths
import com.workflow.orchestrator.core.delegation.DelegationServer
import com.workflow.orchestrator.core.settings.CrossIdeDelegationSettingsListener
import com.workflow.orchestrator.core.settings.PluginSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.workflow.orchestrator.agent.ui.DelegatedStartOutcome
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Test-seam abstraction over [com.workflow.orchestrator.agent.ui.AgentController.startDelegatedSession].
 *
 * Production wires this to the live [com.workflow.orchestrator.agent.ui.AgentController]; headless
 * JUnit tests inject a fake (via [DelegationInboundService.testDelegatedSessionStarter]) because the
 * real controller is a UI service that isn't constructed without a live Application/EDT. The signature
 * mirrors the controller method exactly so [DelegationInboundService.handleConnect] consumes both paths
 * identically: it returns a [DelegatedStartOutcome], delivers the started session id through
 * [onSessionStarted], replies on the channel via [replyWith], and drives the terminal result via
 * [onResult].
 */
fun interface DelegatedSessionStarter {
    suspend fun start(
        request: String,
        metadata: DelegationMetadata,
        replyWith: suspend (DelegationMessage) -> Unit,
        onResult: suspend (DelegationMessage.Result) -> Unit,
        onSessionStarted: ((String) -> Unit)?,
        /**
         * PART 2 — fired on a busy decline with the in-flight task descriptor, so
         * [DelegationInboundService.handleConnect] can compose a self-describing `ide_b_busy:`
         * reason naming what IDE-B was busy with. Null/absent → generic fallback wording.
         */
        onBusy: ((com.workflow.orchestrator.agent.ui.BusyInfo) -> Unit)?,
    ): DelegatedStartOutcome
}

/**
 * Test-seam abstraction over [com.workflow.orchestrator.agent.ui.AgentController.resumeDelegatedSession]
 * — the resurrection counterpart of [DelegatedSessionStarter]. Used by [DelegationInboundService.handleChannelResume]
 * to RESUME a previously-completed delegated session (Fix 3 — true continuation) and continue it with a
 * follow-up user turn, instead of replying [DelegationMessage.SessionClosed].
 *
 * Production wires this to the live [com.workflow.orchestrator.agent.ui.AgentController]; headless tests
 * inject a fake (via [DelegationInboundService.testDelegatedResumeStarter]). The signature mirrors
 * [DelegatedSessionStarter] but adds the persisted [sessionId] to resume and the [userTurnText] follow-up
 * to append. It returns a [DelegatedStartOutcome]: [DelegatedStartOutcome.STARTED] when the resume kicked
 * off (busy-gate passed + session re-opened), or [DelegatedStartOutcome.DECLINED_TIMEOUT] when IDE-B's tab
 * is busy with another task (graceful decline — never hijack a running session).
 */
fun interface DelegatedResumeStarter {
    suspend fun resume(
        sessionId: String,
        userTurnText: String,
        metadata: DelegationMetadata,
        replyWith: suspend (DelegationMessage) -> Unit,
        onResult: suspend (DelegationMessage.Result) -> Unit,
        onSessionStarted: ((String) -> Unit)?,
        /**
         * PART 2 — fired on a busy decline with the in-flight task descriptor, so
         * [DelegationInboundService.handleChannelResume] can compose a self-describing
         * `ide_b_busy:` reason. Null/absent → generic fallback wording.
         */
        onBusy: ((com.workflow.orchestrator.agent.ui.BusyInfo) -> Unit)?,
    ): DelegatedStartOutcome
}

/**
 * PART 2 — busy-enrichment. Composes the SINGLE coherent `ide_b_busy:` reason token IDE-B sends
 * when it declines an incoming delegation because its agent tab is busy.
 *
 * The reason NAMES the in-flight task and — critically — echoes the in-flight task's delegator
 * session id, so IDE-A can recognize the blocker as ITS OWN earlier task (matching it against
 * `list_handles`' `bSessionId`, or the delegator session id it sent with task-1).
 *
 * Wording (when the descriptor is available):
 *   - in-flight task is itself delegated:
 *       `ide_b_busy: agent tab is busy running session <sid> ('<title>'), delegated by <repo>
 *        session <delegatorSessionId>; user did not click Start within <N>s`
 *   - in-flight task is a LOCAL (non-delegated) session — omit the delegator clause:
 *       `ide_b_busy: agent tab is busy running session <sid> ('<title>'); user did not click
 *        Start within <N>s`
 *   - descriptor null (e.g. resume path with no captured info) → [genericFallback].
 *
 * Leads with `ide_b_busy:` and the in-flight task identity so the trailing accept-window note is
 * not misread as "you had <N>s and ignored it". The literal "user did not click Start within <N>s"
 * is the actual mechanism (busy tab → Start prompt → not clicked within `ACCEPT_WINDOW_MS`); the
 * real `ACCEPT_WINDOW_MS` value is used, never a hardcoded number.
 *
 * `ide_b_busy` is a clean PEER reason token (see [classifyResumeCloseReason]'s known-token set) —
 * it must NOT be conflated with `session_closed:` / `declined_timeout:`.
 */
internal fun composeBusyDeclineReason(
    busyInfo: com.workflow.orchestrator.agent.ui.BusyInfo?,
    genericFallback: String = "ide_b_busy: agent tab is busy with another task; " +
        "the user did not accept the takeover within ${com.workflow.orchestrator.agent.ui.AgentController.ACCEPT_WINDOW_MS / 1000}s",
): String {
    if (busyInfo == null) return genericFallback
    val windowSeconds = com.workflow.orchestrator.agent.ui.AgentController.ACCEPT_WINDOW_MS / 1000
    val sessionPart = busyInfo.inFlightSessionId?.let { sid ->
        val titlePart = busyInfo.inFlightTitle?.takeIf { it.isNotBlank() }?.let { " ('$it')" } ?: ""
        "session $sid$titlePart"
    } ?: return genericFallback
    val delegatorClause = if (busyInfo.inFlightDelegatorSessionId != null) {
        val repo = busyInfo.inFlightDelegatorRepo ?: "unknown"
        ", delegated by $repo session ${busyInfo.inFlightDelegatorSessionId}"
    } else ""
    return "ide_b_busy: agent tab is busy running $sessionPart$delegatorClause; " +
        "the user did not accept the takeover within ${windowSeconds}s"
}

/**
 * Per-project service. Manages the lifecycle of [DelegationServer] for this
 * project and routes incoming Connect messages through the Accept dialog into
 * new delegated AgentSessions.
 *
 * - Subscribes to [CrossIdeDelegationSettingsListener] for runtime toggle.
 * - Caller (a StartupActivity registered in plugin.xml) invokes [start].
 *
 * Spec: docs/superpowers/specs/2026-05-22-cross-ide-agent-delegation-design.md §3.2, §5.4, §6.
 */
@Service(Service.Level.PROJECT)
class DelegationInboundService(
    private val project: Project,
    private val cs: CoroutineScope,
) {
    private val settings get() = project.getService(PluginSettings::class.java).state
    private var server: DelegationServer? = null

    // ── On-demand inbound consent (Plan 6 Task 4) ─────────────────────────────

    /**
     * Single-use preauth registry. When IDE-B's user consents to a specific
     * inbound delegation (via the doorbell consent dialog), the consented
     * `nonce` is recorded here. The eventual [DelegationMessage.Connect] carrying
     * that same `preauthNonce` skips the normal Accept dialog — but only ONCE.
     * A leaked, multi-use nonce would let a delegator bypass consent on every
     * subsequent connection, so [consumePreauth] removes the nonce atomically and
     * returns true at most once per recorded nonce.
     */
    private val preauthNonces = ConcurrentHashMap.newKeySet<String>()

    /**
     * True when the delegation socket was bound by [startTransient] (consent
     * "Allow once") rather than because [PluginSettings.enableInboundCrossIdeDelegation]
     * is on. A transient bind is torn down once no delegated sessions remain, and
     * the inbound setting is never persisted.
     */
    @Volatile
    private var transient: Boolean = false

    /**
     * Post-completion retention window for a transient ("Allow once") bind. When the last
     * delegated session on a transient socket completes, the socket is NOT torn down
     * immediately — it stays bound for this long so IDE-A can CONTINUE the same session
     * (resurrectAndContinue → ChannelResume) within the continuation window even though
     * IDE-B accepted the original delegation transiently. Mirrors the outbound
     * [DelegationOutboundService.TRANSCRIPT_RETENTION_MILLIS] (~30 min) — kept as a
     * separate (non-`const`) field so tests can inject a short value AND so we never inline
     * a cross-class `const` that a test reads (the Gradle build-cache `NoSuchMethodError`
     * trap). Test-settable; production uses the 30-min default.
     */
    internal var transientRetentionMillis: Long = DEFAULT_TRANSIENT_RETENTION_MILLIS

    /**
     * The scheduled-unbind coroutine for a transient bind whose last session has completed.
     * Non-null only during the retention window. Cancelled (window reset) when a new delegated
     * session starts before expiry, and cancelled + cleared on immediate teardown (project close
     * or inbound setting turned OFF). Guarded by the instance lock (`@Synchronized`).
     */
    private var pendingTransientUnbind: kotlinx.coroutines.Job? = null

    /** Record a consented preauth nonce. The matching Connect skips the Accept dialog once. */
    fun recordPreauth(nonce: String) {
        preauthNonces.add(nonce)
    }

    /**
     * Consume a preauth nonce. Returns true only the FIRST time a recorded nonce
     * is presented (single-use security gate); a null or unknown nonce → false.
     */
    fun consumePreauth(nonce: String?): Boolean = nonce != null && preauthNonces.remove(nonce)

    // ── Per-session IPC channel registry (Plan 2 Task 4) ──────────────────────

    private data class SessionChannel(
        val sessionId: String,
        val replyWith: suspend (DelegationMessage) -> Unit,
        val pendingToken: PendingQuestionToken,
        val heartbeat: HeartbeatScheduler?,
        /**
         * Spec §6.1 ordering gate. Flipped to `true` by [stopHeartbeatForSession],
         * [unregisterSessionChannel], and [closeAllForProjectClose] BEFORE calling
         * [HeartbeatScheduler.stop]. This ensures any tick currently between
         * `delay()` and `sendMessage()` in the scheduler coroutine sees the gate
         * and bails without writing a spurious heartbeat to the wire.
         */
        val closed: java.util.concurrent.atomic.AtomicBoolean =
            java.util.concurrent.atomic.AtomicBoolean(false),
    )

    private val sessionChannels = ConcurrentHashMap<String, SessionChannel>()

    init {
        project.messageBus.connect().subscribe(
            CrossIdeDelegationSettingsListener.TOPIC,
            object : CrossIdeDelegationSettingsListener {
                override fun inboundSettingChanged(enabled: Boolean) {
                    if (enabled) start() else stop()
                }
            },
        )
    }

    @Synchronized
    fun start() {
        if (server != null) return
        if (!settings.enableInboundCrossIdeDelegation) return
        bindServer()
    }

    /**
     * Bind the delegation socket WITHOUT persisting [PluginSettings.enableInboundCrossIdeDelegation]
     * (Plan 6 Task 4). Used by the doorbell's "Allow once" consent path: IDE-B binds the
     * delegation socket just for this consented delegation, then tears it down via
     * [stopIfTransientAndIdle] once the delegated session ends. Idempotent — early-returns
     * if the server is already bound (whether persistent or transient).
     */
    @Synchronized
    fun startTransient() {
        if (server != null) return
        transient = true
        bindServer()
    }

    /**
     * Socket-binding body shared by [start] (setting-gated) and [startTransient] (no gate).
     * Must only be called while holding the instance lock (both callers are `@Synchronized`)
     * and after the `server == null` guard.
     */
    private fun bindServer() {
        val projectPath = project.basePath ?: run {
            LOG.warn("Project has no basePath; cannot start DelegationInboundService")
            return
        }
        val socketPath = DelegationPaths.socketFor(Path.of(projectPath))
        val srv = DelegationServer(
            socketPath = socketPath,
            projectPath = projectPath,
            onConnect = { connect, replyWith, readMessage, closeChannel ->
                handleConnect(connect, replyWith, readMessage, closeChannel)
            },
            onChannelResume = { resume, replyWith, readMessage, closeChannel ->
                handleChannelResume(resume, replyWith, readMessage, closeChannel)
            },
            scope = cs,
        )
        srv.start()
        server = srv
    }

    @Synchronized
    fun stop() {
        // Immediate teardown also cancels any in-flight retention window so a scheduled
        // unbind can't fire after the socket is already gone (idempotent stop()).
        pendingTransientUnbind?.cancel()
        pendingTransientUnbind = null
        server?.stop()
        server = null
        transient = false
    }

    /**
     * A transient ("Allow once") bind whose last delegated session has just completed must NOT be
     * torn down immediately: IDE-A may continue the SAME session (resurrectAndContinue →
     * ChannelResume) within the continuation window even though IDE-B accepted the original
     * delegation transiently. Tearing the socket down at session end is the bug the tester hit
     * (`ide_b_not_running` despite IDE-B being alive).
     *
     * Instead, when a transient bind goes idle (`activeSessionCount == 0`) we keep the socket bound
     * and schedule an unbind [transientRetentionMillis] later. If a new delegated session starts (or
     * a continuation arrives) before the window expires, the pending unbind is cancelled and the
     * window resets — see [registerSessionChannel] and the `count > 0` branch below.
     *
     * SECURITY: keeping the socket bound does NOT widen the consent surface. The still-bound socket
     * routes a fresh [DelegationMessage.Connect] through [handleConnect], which is gated exactly as
     * before — it requires a consumed preauth nonce OR the human's Accept dialog; we record no new
     * preauth here. Only a [DelegationMessage.ChannelResume] of an already-known/retained session is
     * accepted without a dialog (via [handleChannelResume]), which is the intended continuation. A
     * brand-new, un-consented delegation is still forced back through the doorbell.
     *
     * No-op for a persistent (setting-on) bind. Wired from AgentService's delegated-session terminal
     * callback (Plan 6 Task 8).
     */
    @Synchronized
    fun stopIfTransientAndIdle(activeSessionCount: Int) {
        if (!transient) return
        if (activeSessionCount > 0) {
            // New/ongoing activity: cancel any pending unbind and keep the socket bound.
            pendingTransientUnbind?.cancel()
            pendingTransientUnbind = null
            return
        }
        // activeSessionCount == 0: keep the socket bound for the retention window, then unbind
        // unless new activity resets it. Cancel any prior pending unbind so we don't stack timers.
        pendingTransientUnbind?.cancel()
        val retention = transientRetentionMillis
        // Self-identifying job: it only tears down if it is STILL the current pending window when it
        // fires (guards against a cancel()+reschedule that raced past this job's delay).
        lateinit var thisJob: kotlinx.coroutines.Job
        thisJob = cs.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            kotlinx.coroutines.delay(retention)
            unbindTransientAfterRetention(thisJob)
        }
        pendingTransientUnbind = thisJob
        thisJob.start()
    }

    /** Cancel and clear any pending transient-unbind window (new activity resets the window). */
    @Synchronized
    private fun cancelPendingTransientUnbind() {
        pendingTransientUnbind?.cancel()
        pendingTransientUnbind = null
    }

    /**
     * Scheduled-unbind body invoked when the retention window elapses. Re-checks under the lock
     * that [expiringJob] is still the active pending window (a later
     * [stopIfTransientAndIdle]/[registerSessionChannel]/[stop] may have cancelled it and scheduled
     * a fresh one, or cleared it) and that the bind is still transient before tearing down.
     */
    @Synchronized
    private fun unbindTransientAfterRetention(expiringJob: kotlinx.coroutines.Job) {
        if (pendingTransientUnbind !== expiringJob) return
        pendingTransientUnbind = null
        if (transient) {
            server?.stop()
            server = null
            transient = false
        }
    }

    /**
     * Visible-for-tests delegated-session starter. Production code resolves the live
     * [com.workflow.orchestrator.agent.ui.AgentController] from [com.workflow.orchestrator.agent.ui.AgentControllerRegistry]
     * (see [handleConnect]); headless tests inject a fake here so they can drive the
     * controller-routed accept path without a live UI service. When null, production
     * behavior is unchanged.
     */
    internal var testDelegatedSessionStarter: DelegatedSessionStarter? = null

    /**
     * Visible-for-tests delegated-session RESUME starter (Fix 3 — true continuation). Production code
     * resolves the live [com.workflow.orchestrator.agent.ui.AgentController] and adapts its
     * `resumeDelegatedSession` (see [handleChannelResume]); headless tests inject a fake so they can
     * drive the resurrection path without a live UI service. When null, production behavior is unchanged.
     */
    internal var testDelegatedResumeStarter: DelegatedResumeStarter? = null

    private suspend fun handleConnect(
        connect: DelegationMessage.Connect,
        replyWith: suspend (DelegationMessage) -> Unit,
        readMessage: suspend () -> DelegationMessage,
        closeChannel: suspend () -> Unit,
    ) {
        // Plan 6 Task 4: a Connect carrying a consented preauth nonce skips the Accept
        // dialog. consumePreauth is single-use, so a replayed nonce falls through to the
        // dialog. A normal (non-doorbell) Connect has preauthNonce=null → preApproved=false.
        val preApproved = consumePreauth(connect.preauthNonce)
        // Show the Accept dialog on the EDT (unless already pre-approved via consent).
        val accepted = if (preApproved) true else withContext(Dispatchers.EDT) {
            val dlg = AcceptDelegationDialog(project, connect)
            dlg.show()
            dlg.isOK
        }
        if (!accepted) {
            replyWith(DelegationMessage.AcceptResult(accepted = false, reason = "user_rejected"))
            // Rejection is a terminal message — close the channel immediately.
            closeChannel()
            return
        }
        val metadata = DelegationMetadata(
            delegatorIde = connect.delegatorIde,
            delegatorRepo = connect.delegatorRepo,
            delegatorSessionId = connect.delegatorSessionId,
            startedAt = System.currentTimeMillis(),
        )

        // Task 4: route the accepted delegation through the IDE-B AgentController so it runs
        // as a NORMAL FOREGROUND session (full agent: tools + IDE-B approval gate + streaming),
        // NOT the old headless AgentService loop. The starter is the controller in production;
        // headless tests inject a fake via [testDelegatedSessionStarter]. When the seam is null
        // (production), activate the Workflow▸Agent tool window + resolve the controller on the
        // EDT (handleConnect runs off-EDT on the socket coroutine) and adapt it to the seam type.
        val starter: DelegatedSessionStarter? = testDelegatedSessionStarter ?: run {
            val controller = withContext(Dispatchers.EDT) {
                com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
                    .getToolWindow("Workflow")?.activate {
                        val cm = com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
                            .getToolWindow("Workflow")?.contentManager
                        cm?.contents?.find { it.displayName == "Agent" }?.let { cm.setSelectedContent(it) }
                    }
                com.workflow.orchestrator.agent.ui.AgentControllerRegistry.getInstance(project).controller
            }
            controller?.let { c ->
                DelegatedSessionStarter { request, md, reply, onResult, onStarted, onBusy ->
                    c.startDelegatedSession(request, md, reply, onResult, onStarted, onBusy)
                }
            }
        }
        if (starter == null) {
            // The Agent tab has never been opened in this IDE window, so there is no controller
            // to drive a foreground session. Fail the delegation cleanly rather than silently
            // dropping it.
            LOG.warn("handleConnect: AgentController unavailable for project ${project.name}; failing delegation")
            replyWith(
                DelegationMessage.Result(
                    status = DelegationMessage.ResultStatus.FAILED,
                    reason = "ide_b_agent_unavailable",
                )
            )
            closeChannel()
            return
        }

        // F1: close the socket channel after writing the terminal Result so the FD is
        // released immediately rather than leaking until JVM exit.
        // The sessionIdHolder indirection is needed because onResult is wired before the
        // local session id is known. With the controller path, startDelegatedSession no longer
        // returns the sid synchronously — the agent loop launches asynchronously on the EDT/IO,
        // so we await the sid via an onSessionStarted callback (CompletableDeferred) and use it
        // for both the AcceptResult bSessionId and the inbound read-loop.
        val sessionIdHolder = java.util.concurrent.atomic.AtomicReference<String>()
        val sessionIdReady = CompletableDeferred<String>()
        // PART 2: capture the in-flight descriptor handed up by the busy gate so the decline reply
        // can name what IDE-B was busy with (and echo the in-flight task's delegator session id).
        val busyInfoHolder = java.util.concurrent.atomic.AtomicReference<com.workflow.orchestrator.agent.ui.BusyInfo?>()
        // startDelegatedSession is a suspend fun: for an idle tab it returns STARTED promptly;
        // for a busy tab it surfaces a top-bar prompt and SUSPENDS HERE for up to
        // AgentController.ACCEPT_WINDOW_MS (< IDE-A's connectAndAwaitAccept timeout) waiting for
        // the human to click Start. The socket coroutine simply waits — no background execution.
        // I5: guard against a non-cancellation throw from the starter. Without this an uncaught
        // throw bubbles to DelegationServer's connection handler, which only closes the socket —
        // IDE-A then sees a generic EOF instead of a clean FAILED. CancellationException is
        // re-thrown so coroutine cancellation still propagates.
        val outcome = try {
            starter.start(
                request = connect.request,
                metadata = metadata,
                replyWith = replyWith,
                onResult = { result ->
                    stopHeartbeatForSession(sessionIdHolder.get() ?: "")
                    replyWith(result)
                    closeChannel()
                },
                onSessionStarted = { sid ->
                    sessionIdHolder.set(sid)
                    sessionIdReady.complete(sid)
                },
                onBusy = { info -> busyInfoHolder.set(info) },
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            LOG.warn("handleConnect: delegated-session starter threw for project ${project.name}", e)
            replyWith(
                DelegationMessage.Result(
                    status = DelegationMessage.ResultStatus.FAILED,
                    reason = "delegated_start_error: ${e.message ?: e::class.simpleName}",
                )
            )
            closeChannel()
            return
        }
        if (outcome == DelegatedStartOutcome.DECLINED_TIMEOUT) {
            // Busy tab, human did not click Start within the accept window. Decline cleanly so
            // IDE-A's accept-await resolves (rather than hanging on a session that never starts).
            // onSessionStarted never fired, so do NOT await sessionIdReady.
            // PART 2 — busy-enrichment: compose a SINGLE coherent `ide_b_busy:` reason FROM the
            // in-flight descriptor so IDE-A can recognize the blocker as its OWN earlier task (it
            // echoes the in-flight task's delegator session id). Falls back to the generic string
            // when the descriptor is unavailable, so nothing regresses.
            replyWith(
                DelegationMessage.AcceptResult(
                    accepted = false,
                    reason = composeBusyDeclineReason(busyInfoHolder.get()),
                )
            )
            closeChannel()
            return
        }
        // STARTED (idle tab, or Start clicked within the window): await the sid before the
        // read-loop runs. onSessionStarted fires promptly on the happy path. C1: bound the await
        // so a failed EDT launch (runDelegatedNow throwing before onSessionStarted, or the scope
        // cancelled on project close) can't hang this socket coroutine forever holding IDE-A's
        // channel open — on timeout, reply a clean FAILED and close.
        val localSessionId = withTimeoutOrNull(
            com.workflow.orchestrator.agent.ui.AgentController.SESSION_START_TIMEOUT_MS
        ) { sessionIdReady.await() }
        if (localSessionId == null) {
            LOG.warn("handleConnect: delegated session id not delivered within " +
                "${com.workflow.orchestrator.agent.ui.AgentController.SESSION_START_TIMEOUT_MS}ms " +
                "for project ${project.name}; failing")
            replyWith(
                DelegationMessage.Result(
                    status = DelegationMessage.ResultStatus.FAILED,
                    reason = "session_start_timeout",
                )
            )
            closeChannel()
            return
        }
        // Plan 4: include bSessionId so IDE-A can persist the link for CHANNEL_RESUME.
        replyWith(DelegationMessage.AcceptResult(accepted = true, bSessionId = localSessionId))

        // Read incoming Answer / FetchTranscript / UserTurn messages from IDE-A until the
        // channel closes or throws (EOF / socket error = session over).
        // Plan 1 F8: outer try/finally guarantees closeChannel + unregisterSessionChannel
        // run on every exit path — normal loop end, exception, or unexpected throw.
        // H2: factor the read-loop body into runInboundReadLoop so handleChannelResume can
        // reuse it without duplication.
        try {
            runInboundReadLoop(localSessionId, readMessage, replyWith)
        } finally {
            // Plan 1 F8: guarantee channel cleanup even on unexpected exit paths.
            // Both calls are idempotent — closeChannel() is safe on already-closed
            // sockets; unregisterSessionChannel returns silently for unknown ids.
            try { closeChannel() } catch (e: Exception) {
                LOG.warn("Inbound read-loop finally: closeChannel threw for session $localSessionId", e)
            }
            unregisterSessionChannel(localSessionId)
        }
    }

    /**
     * Shared inbound read-loop body for [handleConnect] and [handleChannelResume].
     *
     * Reads frames from [readMessage] until the channel is closed or throws. Dispatches:
     * - [DelegationMessage.Answer] → [deliverAnswer]
     * - [DelegationMessage.FetchTranscript] → [handleFetchTranscript]
     * - [DelegationMessage.UserTurn] → injects the text as a real user turn into the
     *   session's agent loop via [AgentService.enqueueNudgeForSession]. This is
     *   semantically equivalent to the human in IDE-B typing a follow-up message.
     *   (H3 fix: was silently dropped by the else→WARN branch.)
     * - else → WARN log
     *
     * Exceptions are caught per the F3 pattern (ClosedChannelException /
     * AsynchronousCloseException are normal termination; all other exceptions are WARN).
     *
     * Plan 4 review fix H2/H3.
     */
    /** Visible-for-tests: made internal so unit tests can call the loop directly. */
    internal suspend fun runInboundReadLoop(
        localSessionId: String,
        readMessage: suspend () -> DelegationMessage,
        replyWith: suspend (DelegationMessage) -> Unit,
    ) {
        try {
            while (true) {
                val msg = readMessage()
                when (msg) {
                    is DelegationMessage.Answer -> {
                        val delivered = deliverAnswer(localSessionId, msg.questionId, msg.text)
                        if (!delivered) {
                            LOG.debug(
                                "deliverAnswer: no pending question for qid=${msg.questionId} " +
                                    "on session $localSessionId (race or stale message)"
                            )
                        }
                    }
                    is DelegationMessage.FetchTranscript -> {
                        // Use THIS channel's own session id, not the remote-supplied
                        // msg.sessionId: the delegator (IDE-A) puts ITS session id on the wire,
                        // which would resolve to a non-existent dir here and return "no
                        // conversation history on disk". The channel already knows which local
                        // session it serves; that is authoritative.
                        handleFetchTranscript(
                            sessionId = localSessionId,
                            requestId = msg.requestId,
                            replyWith = replyWith,
                        )
                    }
                    is DelegationMessage.UserTurn -> {
                        // H3 fix: deliver the continuation text from IDE-A as a real user
                        // turn in this session's agent loop. Semantically equivalent to the
                        // human in IDE-B typing the next message in the chat input.
                        // enqueueNudgeForSession routes through the steering queue which
                        // calls contextManager.addUserMessage at the next iteration boundary.
                        LOG.debug(
                            "UserTurn received on inbound channel for session $localSessionId " +
                                "(${msg.text.take(60)}…)"
                        )
                        val agentService = project.getService(AgentService::class.java)
                        agentService.enqueueNudgeForSession(localSessionId, msg.text)
                    }
                    else -> {
                        LOG.warn(
                            "Unexpected message on inbound channel post-Accept for session " +
                                "$localSessionId: ${msg::class.simpleName}"
                        )
                    }
                }
            }
        } catch (e: java.nio.channels.ClosedChannelException) {
            // F3 fix: normal termination — closeChannel() interrupted readMessage().
            LOG.debug("Inbound read-loop ended (channel closed) for session $localSessionId")
        } catch (e: java.nio.channels.AsynchronousCloseException) {
            // F3 fix: normal termination on Windows/async close path.
            LOG.debug("Inbound read-loop ended (async close) for session $localSessionId")
        } catch (e: Exception) {
            LOG.warn("Inbound read-loop failed unexpectedly for session $localSessionId", e)
        }
    }

    // ── Public IPC-channel API ────────────────────────────────────────────────

    /**
     * Register a reply channel for a delegated session. Called by [AgentService.startDelegatedSession]
     * before the agent loop is launched. Returns the [PendingQuestionToken] for the session so
     * callers that need direct token access (e.g. tests) can hold it.
     */
    fun registerSessionChannel(
        sessionId: String,
        replyWith: suspend (DelegationMessage) -> Unit,
    ): PendingQuestionToken {
        // A new (or continuation) delegated session arriving before a transient retention window
        // expires must cancel the pending unbind so the socket isn't torn down out from under it.
        cancelPendingTransientUnbind()
        val token = PendingQuestionToken()
        // Build the closed flag first so the HeartbeatScheduler can reference it via
        // the isClosed lambda before the SessionChannel itself is in the map.
        val closed = java.util.concurrent.atomic.AtomicBoolean(false)
        val hb = HeartbeatScheduler(
            sessionId = sessionId,
            scope = cs,
            isClosed = { closed.get() },
            sendMessage = replyWith,
        )
        sessionChannels[sessionId] = SessionChannel(sessionId, replyWith, token, hb, closed)
        hb.start()
        return token
    }

    /**
     * Unregister and cancel any pending question for a session. Called from the
     * [AgentService.startDelegatedSession] finally-block when the session ends.
     */
    fun unregisterSessionChannel(sessionId: String) {
        sessionChannels.remove(sessionId)?.let { sc ->
            // Spec §6.1: gate BEFORE stop so an in-flight tick between delay() and
            // sendMessage() sees the flag and bails without writing a spurious heartbeat.
            sc.closed.set(true)
            sc.heartbeat?.stop()
            sc.pendingToken.armedQuestionId?.let { qid -> sc.pendingToken.cancel(qid, "session_ended") }
        }
    }

    /**
     * Send a [DelegationMessage.Question] to IDE-A and suspend until an Answer arrives
     * (or the coroutine is cancelled because the session ended).
     *
     * Called by [com.workflow.orchestrator.agent.tools.builtin.AskQuestionsTool] when it
     * detects that the current session is delegated.
     *
     * Throws [IllegalStateException] if no channel is registered or another question is
     * already in-flight (the agent loop is single-threaded so this should never happen in
     * normal operation).
     */
    suspend fun routeQuestion(
        sessionId: String,
        question: String,
        options: List<String> = emptyList(),
    ): String {
        val sc = sessionChannels[sessionId]
            ?: error("routeQuestion: no registered channel for session $sessionId")
        val questionId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<String>()
        if (!sc.pendingToken.armIfClear(questionId, deferred)) {
            // F4 fix: include the pending questionId so the LLM can recognize which
            // question to answer first before raising a new one.
            error(
                "routeQuestion: question ${sc.pendingToken.armedQuestionId} is already pending on " +
                    "session $sessionId; answer it first before raising a new question"
            )
        }

        // Plan 4 §5.5: surface via input banner instead of inline-nudge.
        val delegatorRepo = project.getService(AgentService::class.java)
            .findDelegationMetadata(sessionId)?.delegatorRepo
        notifyDelegationQuestionPending(sessionId, active = true, delegatorRepo = delegatorRepo)
        // Leg (b): narrate "↗ Asked {delegatorRepo}" on IDE-B's own panel as a delegation card.
        notifyDelegatedQuestionAsked(sessionId, questionId, delegatorRepo, question, options)

        sc.replyWith(DelegationMessage.Question(questionId, question, options))
        return deferred.await()
    }

    /**
     * Deliver an answer that arrived on the inbound read-loop.
     * Returns true if this call won the race; false if the question was already
     * answered locally or no question was pending.
     */
    fun deliverAnswer(sessionId: String, questionId: String, answer: String): Boolean {
        val sc = sessionChannels[sessionId] ?: return false
        val resolved = sc.pendingToken.tryResolve(questionId, answer)
        if (resolved) {
            // Plan 4 §5.5: clear the input banner once the question is answered.
            notifyDelegationQuestionPending(sessionId, active = false, delegatorRepo = null)
            // Leg (c): narrate "↘ {delegatorRepo} answered" on IDE-B's own panel + flip the
            // matching ASKED card to resolved.
            val delegatorRepo = project.getService(AgentService::class.java)
                .findDelegationMetadata(sessionId)?.delegatorRepo
            notifyDelegatedAnswer(sessionId, questionId, delegatorRepo, answer)
        }
        return resolved
    }

    /**
     * Returns true if [sessionId] has a pending unanswered question.
     * Used by Task 7 IDE-B short-circuit logic.
     */
    fun hasPendingQuestion(sessionId: String): Boolean =
        sessionChannels[sessionId]?.pendingToken?.armedQuestionId != null

    /**
     * Called by [DelegationInboundProjectCloseListener] when the project window
     * closes. Writes a terminal [DelegationMessage.Result] with `reason="project_closed"`
     * to every active inbound channel, then clears the registry. This is distinct
     * from full IDE process death (which is handled by socket EOF on the IDE-A side).
     *
     * Plan 3 spec §5.2.
     */
    suspend fun closeAllForProjectClose() {
        val snapshot = sessionChannels.toMap()
        sessionChannels.clear()
        for ((sessionId, channel) in snapshot) {
            // Spec §6.1: gate BEFORE stop so an in-flight tick sees the flag and bails.
            channel.closed.set(true)
            channel.heartbeat?.stop()
            try {
                channel.replyWith(
                    DelegationMessage.Result(
                        status = DelegationMessage.ResultStatus.FAILED,
                        reason = "project_closed",
                    )
                )
            } catch (e: Exception) {
                LOG.warn("closeAllForProjectClose: failed to write FAILED for $sessionId", e)
            }
            channel.pendingToken.armedQuestionId?.let { qid ->
                channel.pendingToken.cancel(qid, "project_closed")
            }
        }
        // Project close must unbind a transient socket IMMEDIATELY (no retention) — the retention
        // window only exists to bridge IDE-A's reconnect while THIS IDE-B project stays open. Once
        // the project closes there is nothing to continue. stop() is a no-op for a persistent bind
        // beyond clearing the (absent) pending window, but the persistent socket is torn down by
        // the platform's service disposal, not here. Cancel + unbind transient now.
        if (transient) stop() else cancelPendingTransientUnbind()
    }

    /**
     * Called by the chat-input handler when the human in IDE-B types an answer
     * into a delegated session's input while a question is pending. Wins the
     * race via [PendingQuestionToken.tryResolve]; on success, sends an
     * [DelegationMessage.AnswerCanceled] back to IDE-A so it can rescind any
     * pending confirmation UI.
     *
     * Returns true if the local answer won the race (the deferred was resolved
     * by this call), false if no question was pending or someone else already
     * answered.
     *
     * Spec: §3.2 short-circuit + §4.2 race semantics.
     */
    suspend fun localAnswer(sessionId: String, answer: String): Boolean {
        val sc = sessionChannels[sessionId] ?: return false
        // F2 fix: use tryResolveCurrent to atomically clear the slot and get the questionId
        // in a single CAS — eliminates the TOCTOU between armedQuestionId read and tryResolve.
        val resolvedQid = sc.pendingToken.tryResolveCurrent(answer) ?: return false
        // Plan 4 §5.5: clear the input banner now that the question is answered locally.
        notifyDelegationQuestionPending(sessionId, active = false, delegatorRepo = null)
        try {
            sc.replyWith(
                DelegationMessage.AnswerCanceled(
                    questionId = resolvedQid,
                    reason = "answered_locally",
                )
            )
        } catch (e: Exception) {
            LOG.warn("localAnswer: failed to send AnswerCanceled for $resolvedQid on session $sessionId", e)
        }
        return true
    }

    /**
     * Stop the heartbeat scheduler for [sessionId] WITHOUT removing the registry
     * entry. Called by the `onResult` lambda in [handleConnect] immediately before
     * writing the terminal `Result` frame, so no heartbeat tick races the terminal
     * write on the same channel.
     *
     * Plan 3 spec §6.1 cancellation ordering.
     */
    fun stopHeartbeatForSession(sessionId: String) {
        sessionChannels[sessionId]?.let { sc ->
            // Spec §6.1: gate the scheduler BEFORE cancelling, so any in-flight tick
            // currently between delay() and sendMessage() sees the gate and bails.
            sc.closed.set(true)
            sc.heartbeat?.stop()
        }
    }

    // ── FetchTranscript support (Plan 3 Task 7) ─────────────────────────────────

    /**
     * Visible-for-tests session-directory resolver. Production code reads from
     * the real session store via [defaultSessionDir]; tests stub a lambda for
     * deterministic file fixtures.
     */
    internal var testSessionDirResolver: ((String) -> Path?)? = null

    private fun resolveSessionDir(sessionId: String): Path? =
        testSessionDirResolver?.invoke(sessionId)
            ?: defaultSessionDir(sessionId)

    private fun defaultSessionDir(sessionId: String): Path? {
        val basePath = project.basePath ?: return null
        // {ProjectIdentifier.agentDir(basePath)}/sessions/{sessionId}/
        // Verified at AgentService.kt:2751 which already follows this pattern.
        val agentDir = com.workflow.orchestrator.core.util.ProjectIdentifier.agentDir(basePath)
        return java.io.File(agentDir, "sessions/$sessionId").toPath()
    }

    /**
     * Handle an inbound [DelegationMessage.FetchTranscript]. Writes the session's
     * full conversation history to a sidecar `transcript-export.json` under the
     * same session directory and replies on the channel that RECEIVED the request
     * (not via [sessionChannels] lookup, since the request may target a closed
     * session whose channel no longer exists).
     *
     * Plan 3 spec §5.7.
     */
    suspend fun handleFetchTranscript(
        sessionId: String,
        requestId: String,
        replyWith: suspend (DelegationMessage) -> Unit,
    ) {
        val sessionDir = resolveSessionDir(sessionId)
        if (sessionDir == null) {
            replyWith(
                DelegationMessage.FetchTranscriptReply(
                    requestId = requestId,
                    status = "not_found",
                    error = "session $sessionId not in sessions index",
                )
            )
            return
        }
        val historyFile = sessionDir.resolve("api_conversation_history.json")
        if (!Files.exists(historyFile)) {
            replyWith(
                DelegationMessage.FetchTranscriptReply(
                    requestId = requestId,
                    status = "not_found",
                    error = "no conversation history on disk for $sessionId",
                )
            )
            return
        }
        val exportPath = sessionDir.resolve("transcript-export.json")
        try {
            withContext(Dispatchers.IO) {
                Files.copy(historyFile, exportPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
            replyWith(
                DelegationMessage.FetchTranscriptReply(
                    requestId = requestId,
                    status = "ok",
                    transcriptPath = exportPath.toAbsolutePath().toString(),
                )
            )
        } catch (e: Exception) {
            LOG.warn("handleFetchTranscript: copy failed for $sessionId", e)
            replyWith(
                DelegationMessage.FetchTranscriptReply(
                    requestId = requestId,
                    status = "error",
                    error = "io_error: ${e.message ?: e.javaClass.simpleName}",
                )
            )
        }
    }

    /**
     * Handle an incoming [DelegationMessage.ChannelResume] from IDE-A. Dispatches:
     *  - Live session → [DelegationMessage.ChannelResumed] (channel stays open for ongoing exchange)
     *  - Terminal-but-persisted delegated session → RESURRECT (Fix 3): reply
     *    [DelegationMessage.ChannelResumed], read the follow-up [DelegationMessage.UserTurn], and
     *    resume-and-continue the persisted conversation via [resumeDelegatedSessionViaController].
     *  - Unknown sessionId → [DelegationMessage.SessionNotFound]
     *
     * H2 fix (Plan 4 review): after replying ChannelResumed to a live session, the original
     * implementation returned immediately without reading further frames from the resumed
     * channel. This meant follow-up Answer / FetchTranscript / UserTurn messages sent by
     * IDE-A on the new channel would never be dispatched. The fix runs [runInboundReadLoop]
     * on the resumed channel, wrapped in the same Plan-1-F8 try/finally that handleConnect uses.
     *
     * Fix 3 (true continuation): a terminated session is no longer a dead end. A persisted
     * delegated session (its HistoryItem carries `delegated` metadata) is RE-OPENED and continued
     * with the follow-up turn IDE-A sends right after the resume handshake — the same conversation,
     * resumed. The inbound consent dialog is SKIPPED (the human accepted this channel when the
     * delegation was first established). IDE-B's busy-gate still applies: if its tab is actively
     * running another task, the resume declines gracefully (we never hijack a running session).
     *
     * Old-channel note: The old [replyWith] closure stored in [sessionChannels] is replaced
     * by the new one from this resume call. The underlying old socket is assumed dead by virtue
     * of IDE-A's restart (the OS reclaims it); the read-loop on the original handleConnect
     * will have already hit EOF and exited. If future versions need explicit old-socket close,
     * store the old channel reference per-session and close it here.
     *
     * Plan 4 spec §3.3 + Fix 3 (2026-05-30 continuation campaign).
     */
    suspend fun handleChannelResume(
        msg: DelegationMessage.ChannelResume,
        replyWith: suspend (DelegationMessage) -> Unit,
        readMessage: suspend () -> DelegationMessage,
        closeChannel: suspend () -> Unit,
    ) {
        val sessionId = msg.sessionId
        val sc = sessionChannels[sessionId]
        if (sc != null) {
            // Session is live — re-attach. The current state is whatever the existing
            // PendingQuestionToken plus session metadata reports.
            val currentState = if (sc.pendingToken.armedQuestionId != null) "AWAITING_ANSWER" else "RUNNING"
            // Replace the registered replyWith with the new channel's replyWith so future
            // messages go to the resumed connection.
            sessionChannels[sessionId] = sc.copy(replyWith = replyWith)
            replyWith(DelegationMessage.ChannelResumed(sessionId, currentState))
            // H2 fix: run the same read-loop body that handleConnect uses so subsequent
            // Answer / FetchTranscript / UserTurn frames on the resumed channel are dispatched.
            // Wrap in try/finally (Plan 1 F8) to guarantee closeChannel runs on every exit path.
            // Note: do NOT close the channel before the read-loop — leave it open for ongoing exchange.
            try {
                runInboundReadLoop(sessionId, readMessage, replyWith)
            } finally {
                try { closeChannel() } catch (e: Exception) {
                    LOG.warn("handleChannelResume finally: closeChannel threw for session $sessionId", e)
                }
            }
            return
        }

        // Session not in live registry — check on-disk index for a terminal delegated record.
        val basePath = project.basePath
        val agentDir: java.io.File? = if (basePath != null)
            com.workflow.orchestrator.core.util.ProjectIdentifier.agentDir(basePath)
        else null
        val item = agentDir?.let {
            com.workflow.orchestrator.agent.session.MessageStateHandler.findHistoryItem(it, sessionId)
        }
        val delegated = item?.delegated
        if (delegated == null) {
            // Truly unknown / pruned — never seen here. (Also reached when this isn't a delegated
            // session; we only resurrect delegated ones since only those carry the delegation marker.)
            replyWith(DelegationMessage.SessionNotFound(sessionId))
            closeChannel()
            return
        }

        // Fix 3: terminated-but-persisted delegated session → RESURRECT and continue.
        //
        // The verdict is SYNCHRONOUS: IDE-A's reattach (resurrectAndContinue) sends ChannelResume
        // IMMEDIATELY followed by the follow-up UserTurn, then blocks on ONE reply. We therefore read
        // the UserTurn FIRST, run the busy-gate + kick off the resume, and only then send the single
        // verdict — ChannelResumed (resume started) or SessionClosed (busy / unavailable / resume
        // failed). This gives the delegating agent an immediate, actionable answer instead of a
        // ChannelResumed-then-SessionClosed sequence racing on the same channel.

        // 1) Read the follow-up user turn IDE-A sends right after ChannelResume.
        val firstFrame = try {
            readMessage()
        } catch (e: Exception) {
            LOG.warn("handleChannelResume: failed reading follow-up turn for $sessionId", e)
            replyWith(
                DelegationMessage.SessionClosed(
                    sessionId = sessionId,
                    closeReason = "resume_protocol_error: follow-up turn not received",
                )
            )
            closeChannel()
            return
        }
        if (firstFrame !is DelegationMessage.UserTurn) {
            LOG.warn("handleChannelResume: expected UserTurn after resume for $sessionId, " +
                "got ${firstFrame::class.simpleName}")
            replyWith(
                DelegationMessage.SessionClosed(
                    sessionId = sessionId,
                    closeReason = "resume_protocol_error: expected a follow-up user turn",
                )
            )
            closeChannel()
            return
        }

        // 2) Resolve the resume seam (controller in production; injected fake in tests).
        val metadata = DelegationMetadata(
            delegatorIde = item.delegated?.delegatorIde ?: "unknown",
            delegatorRepo = item.delegated?.delegatorRepo ?: "unknown",
            delegatorSessionId = item.delegated?.delegatorSessionId ?: sessionId,
            startedAt = System.currentTimeMillis(),
        )
        val starter: DelegatedResumeStarter? = testDelegatedResumeStarter ?: run {
            val controller = withContext(Dispatchers.EDT) {
                com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
                    .getToolWindow("Workflow")?.activate {
                        val cm = com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
                            .getToolWindow("Workflow")?.contentManager
                        cm?.contents?.find { it.displayName == "Agent" }?.let { cm.setSelectedContent(it) }
                    }
                com.workflow.orchestrator.agent.ui.AgentControllerRegistry.getInstance(project).controller
            }
            controller?.let { c ->
                DelegatedResumeStarter { sid, turn, md, reply, onResult, onStarted, onBusy ->
                    c.resumeDelegatedSession(sid, turn, md, reply, onResult, onStarted, onBusy)
                }
            }
        }
        if (starter == null) {
            LOG.warn("handleChannelResume: AgentController unavailable for ${project.name}; cannot resume $sessionId")
            replyWith(
                DelegationMessage.SessionClosed(
                    sessionId = sessionId,
                    closeReason = "ide_b_agent_unavailable",
                )
            )
            closeChannel()
            return
        }

        // 3) Resume-and-continue through the seam (busy-gated; consent SKIPPED). We must NOT reply
        // ChannelResumed before this returns STARTED + delivers a sid, or IDE-A would treat a busy /
        // failed resume as success.
        val sessionIdReady = CompletableDeferred<String>()
        val busyInfoHolder = java.util.concurrent.atomic.AtomicReference<com.workflow.orchestrator.agent.ui.BusyInfo?>()
        val outcome = try {
            starter.resume(
                sessionId = sessionId,
                userTurnText = firstFrame.text,
                metadata = metadata,
                replyWith = replyWith,
                onResult = { result ->
                    stopHeartbeatForSession(sessionId)
                    replyWith(result)
                    closeChannel()
                },
                onSessionStarted = { sid -> sessionIdReady.complete(sid) },
                onBusy = { info -> busyInfoHolder.set(info) },
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // resumeSession returned null (locked / missing / no history) or threw — distinct error.
            LOG.warn("handleChannelResume: resume starter threw for $sessionId", e)
            replyWith(
                DelegationMessage.SessionClosed(
                    sessionId = sessionId,
                    closeReason = "resume_failed: ${e.message ?: e::class.simpleName}",
                )
            )
            closeChannel()
            return
        }
        if (outcome == DelegatedStartOutcome.DECLINED_TIMEOUT) {
            // IDE-B is busy running another task — decline gracefully (never hijack). onSessionStarted
            // never fired, so do NOT await it. IDE-A maps this to a clear "busy" error.
            // PART 2 — busy-enrichment: name the in-flight task (and echo its delegator session id)
            // via the shared composer, so IDE-A can recognize the blocker as its own earlier task.
            // The resume path's generic fallback also names that the resume in particular failed.
            replyWith(
                DelegationMessage.SessionClosed(
                    sessionId = sessionId,
                    closeReason = composeBusyDeclineReason(
                        busyInfoHolder.get(),
                        genericFallback = "ide_b_busy: the agent tab is running another task; could not resume",
                    ),
                )
            )
            closeChannel()
            return
        }
        // STARTED: await the resumed sid, then send the SUCCESS verdict.
        val resumedSid = withTimeoutOrNull(
            com.workflow.orchestrator.agent.ui.AgentController.SESSION_START_TIMEOUT_MS
        ) { sessionIdReady.await() }
        if (resumedSid == null) {
            LOG.warn("handleChannelResume: resumed session id not delivered for $sessionId; failing")
            replyWith(
                DelegationMessage.SessionClosed(
                    sessionId = sessionId,
                    closeReason = "resume_failed: session id not delivered in time",
                )
            )
            closeChannel()
            return
        }
        // The resume is live — NOW send the single success verdict so IDE-A re-attaches its channel
        // and arms its reader for the eventual terminal Result.
        replyWith(DelegationMessage.ChannelResumed(sessionId, "RESUMING"))
        // Run the inbound read-loop for subsequent answer / fetch_transcript / further user turns,
        // mirroring handleConnect's Plan-1-F8 try/finally. The terminal Result already closes the
        // channel via the onResult lambda above; this loop also exits on EOF.
        try {
            runInboundReadLoop(resumedSid, readMessage, replyWith)
        } finally {
            try { closeChannel() } catch (e: Exception) {
                LOG.warn("handleChannelResume finally (resumed): closeChannel threw for $resumedSid", e)
            }
            unregisterSessionChannel(resumedSid)
        }
    }

    /** Visible-for-tests sink. Production uses the JCEF bridge through AgentController. */
    internal var testWebviewPushSink: ((Boolean, String?) -> Unit)? = null

    /**
     * Push the delegation-question-pending state to IDE-B's webview. Called by
     * [routeQuestion] when a question arrives and [deliverAnswer]/[localAnswer]
     * when it resolves.
     *
     * Plan 4 spec §5.5.
     */
    fun notifyDelegationQuestionPending(sessionId: String, active: Boolean, delegatorRepo: String?) {
        testWebviewPushSink?.invoke(active, delegatorRepo)
        // Production: route through AgentControllerRegistry.
        try {
            val controller = com.workflow.orchestrator.agent.ui.AgentControllerRegistry
                .getInstance(project).controller
            controller?.pushDelegationQuestionPending(sessionId, active, delegatorRepo)
        } catch (e: Exception) {
            LOG.warn("notifyDelegationQuestionPending: controller push failed", e)
        }
    }

    /**
     * Leg (b): narrate a question routed to the delegator on IDE-B's OWN panel.
     * Routes through [com.workflow.orchestrator.agent.ui.AgentController.pushDelegatedQuestionAsked].
     * Null-safe when the controller is absent (tests/headless).
     */
    fun notifyDelegatedQuestionAsked(
        sessionId: String,
        questionId: String,
        delegatorRepo: String?,
        question: String,
        options: List<String>,
    ) {
        try {
            val controller = com.workflow.orchestrator.agent.ui.AgentControllerRegistry
                .getInstance(project).controller
            controller?.pushDelegatedQuestionAsked(sessionId, questionId, delegatorRepo, question, options)
        } catch (e: Exception) {
            LOG.warn("notifyDelegatedQuestionAsked: controller push failed", e)
        }
    }

    /**
     * Leg (c): narrate the answer received from the delegator on IDE-B's OWN panel.
     * Routes through [com.workflow.orchestrator.agent.ui.AgentController.pushDelegatedAnswer].
     */
    fun notifyDelegatedAnswer(
        sessionId: String,
        questionId: String,
        delegatorRepo: String?,
        answer: String,
    ) {
        try {
            val controller = com.workflow.orchestrator.agent.ui.AgentControllerRegistry
                .getInstance(project).controller
            controller?.pushDelegatedAnswer(sessionId, questionId, delegatorRepo, answer)
        } catch (e: Exception) {
            LOG.warn("notifyDelegatedAnswer: controller push failed", e)
        }
    }

    companion object {
        private val LOG = Logger.getInstance(DelegationInboundService::class.java)

        /**
         * Default post-completion retention for a transient ("Allow once") inbound bind: 30 min.
         * Deliberately mirrors [DelegationOutboundService.TRANSCRIPT_RETENTION_MILLIS] (the outbound
         * retained-handle window) so the continuation window is symmetric on both sides — IDE-A keeps
         * its [DelegationOutboundService] RetainedHandle for 30 min and IDE-B keeps the transient
         * work socket bound for the same 30 min. Declared as a non-`const` `val` (not `const val`) so
         * a Gradle compile-avoidance build-cache `NoSuchMethodError` can't surface if a test ever
         * inlines it — tests inject [transientRetentionMillis] directly instead.
         */
        @JvmField
        val DEFAULT_TRANSIENT_RETENTION_MILLIS: Long = 30L * 60_000L
    }
}

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
import kotlinx.coroutines.Dispatchers
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
    ): DelegatedStartOutcome
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
        server?.stop()
        server = null
    }

    /**
     * Tear down a transient (consent "Allow once") bind once no delegated sessions
     * remain (Plan 6 Task 4). No-op for a persistent (setting-on) bind. Wired from
     * AgentService's delegated-session terminal callback in Task 8.
     */
    @Synchronized
    fun stopIfTransientAndIdle(activeSessionCount: Int) {
        if (transient && activeSessionCount == 0) {
            stop()
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
                DelegatedSessionStarter { request, md, reply, onResult, onStarted ->
                    c.startDelegatedSession(request, md, reply, onResult, onStarted)
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
            replyWith(DelegationMessage.AcceptResult(accepted = false, reason = "declined_timeout"))
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
                        handleFetchTranscript(
                            sessionId = msg.sessionId,
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
     *  - Terminal session (known from sessions.json with `delegated` metadata) → [DelegationMessage.SessionClosed]
     *  - Unknown sessionId → [DelegationMessage.SessionNotFound]
     *
     * H2 fix (Plan 4 review): after replying ChannelResumed to a live session, the original
     * implementation returned immediately without reading further frames from the resumed
     * channel. This meant follow-up Answer / FetchTranscript / UserTurn messages sent by
     * IDE-A on the new channel would never be dispatched. The fix runs [runInboundReadLoop]
     * on the resumed channel, wrapped in the same Plan-1-F8 try/finally that handleConnect uses.
     *
     * Old-channel note: The old [replyWith] closure stored in [sessionChannels] is replaced
     * by the new one from this resume call. The underlying old socket is assumed dead by virtue
     * of IDE-A's restart (the OS reclaims it); the read-loop on the original handleConnect
     * will have already hit EOF and exited. If future versions need explicit old-socket close,
     * store the old channel reference per-session and close it here.
     *
     * Plan 4 spec §3.3.
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

        // Session not in live registry — check on-disk index for terminal record.
        val basePath = project.basePath
        val agentDir: java.io.File? = if (basePath != null)
            com.workflow.orchestrator.core.util.ProjectIdentifier.agentDir(basePath)
        else null
        val item = agentDir?.let {
            com.workflow.orchestrator.agent.session.MessageStateHandler.findHistoryItem(it, sessionId)
        }
        val delegated = item?.delegated
        if (delegated == null) {
            replyWith(DelegationMessage.SessionNotFound(sessionId))
        } else {
            replyWith(
                DelegationMessage.SessionClosed(
                    sessionId = sessionId,
                    closeReason = delegated.closeReason ?: "closed",
                    summary = null,  // v1 carries summary in Result, not in HistoryItem.delegated
                )
            )
        }
        closeChannel()
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

    companion object {
        private val LOG = Logger.getInstance(DelegationInboundService::class.java)
    }
}

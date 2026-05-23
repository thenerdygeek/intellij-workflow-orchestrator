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
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

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

    // ── Per-session IPC channel registry (Plan 2 Task 4) ──────────────────────

    private data class SessionChannel(
        val sessionId: String,
        val replyWith: suspend (DelegationMessage) -> Unit,
        val pendingToken: PendingQuestionToken,
        val heartbeat: HeartbeatScheduler?,
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

    private suspend fun handleConnect(
        connect: DelegationMessage.Connect,
        replyWith: suspend (DelegationMessage) -> Unit,
        readMessage: suspend () -> DelegationMessage,
        closeChannel: suspend () -> Unit,
    ) {
        // Show the Accept dialog on the EDT.
        val accepted = withContext(Dispatchers.EDT) {
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
        replyWith(DelegationMessage.AcceptResult(accepted = true))

        // Hand off to AgentService to actually start the delegated session.
        // startDelegatedSession now returns the local session ID synchronously and
        // registers the replyWith channel before launching the agent loop.
        val agentService = project.getService(AgentService::class.java)
        val metadata = DelegationMetadata(
            delegatorIde = connect.delegatorIde,
            delegatorRepo = connect.delegatorRepo,
            delegatorSessionId = connect.delegatorSessionId,
            startedAt = System.currentTimeMillis(),
        )
        // F1: close the socket channel after writing the terminal Result so the FD is
        // released immediately rather than leaking until JVM exit.
        // The sessionIdHolder indirection is needed because onResult is passed before
        // startDelegatedSession returns the session ID (forward-reference workaround).
        val sessionIdHolder = java.util.concurrent.atomic.AtomicReference<String>()
        val localSessionId = agentService.startDelegatedSession(
            request = connect.request,
            delegationMetadata = metadata,
            replyWith = replyWith,
            onResult = { result ->
                stopHeartbeatForSession(sessionIdHolder.get() ?: "")
                replyWith(result)
                closeChannel()
            },
        )
        sessionIdHolder.set(localSessionId)

        // Read incoming Answer / AnswerCanceled messages from IDE-A until the channel
        // closes or throws (EOF / socket error = session over).
        // Plan 1 F8: outer try/finally guarantees closeChannel + unregisterSessionChannel
        // run on every exit path — normal loop end, exception, or unexpected throw.
        try {
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
        val hb = HeartbeatScheduler(
            sessionId = sessionId,
            scope = cs,
            sendMessage = replyWith,
        )
        sessionChannels[sessionId] = SessionChannel(sessionId, replyWith, token, hb)
        hb.start()
        return token
    }

    /**
     * Unregister and cancel any pending question for a session. Called from the
     * [AgentService.startDelegatedSession] finally-block when the session ends.
     */
    fun unregisterSessionChannel(sessionId: String) {
        sessionChannels.remove(sessionId)?.let { sc ->
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

        // F7 fix: give the IDE-B human a visual signal that a question is pending and
        // that typed input will short-circuit to answer it. MVP: inject an informational
        // nudge message. Proper banner/placeholder UX is deferred.
        val agentService = project.getService(AgentService::class.java)
        agentService.enqueueNudgeForSession(
            sessionId,
            "A question was forwarded to the delegator's agent. " +
                "Type an answer here to short-circuit and answer it yourself."
        )

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
        return sc.pendingToken.tryResolve(questionId, answer)
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
        sessionChannels[sessionId]?.heartbeat?.stop()
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
                    status = "not_found",
                    error = "io_error: ${e.message ?: e.javaClass.simpleName}",
                )
            )
        }
    }

    companion object {
        private val LOG = Logger.getInstance(DelegationInboundService::class.java)
    }
}

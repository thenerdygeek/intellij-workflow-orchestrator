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
        val localSessionId = agentService.startDelegatedSession(
            request = connect.request,
            delegationMetadata = metadata,
            replyWith = replyWith,
            onResult = { result ->
                replyWith(result)
                closeChannel()
            },
        )

        // Read incoming Answer / AnswerCanceled messages from IDE-A until the channel
        // closes or throws (EOF / socket error = session over).
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
                    else -> {
                        LOG.warn(
                            "Unexpected message on inbound channel post-Accept for session " +
                                "$localSessionId: ${msg::class.simpleName}"
                        )
                    }
                }
            }
        } catch (e: Exception) {
            LOG.debug("Inbound read-loop ended for session $localSessionId", e)
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
        sessionChannels[sessionId] = SessionChannel(sessionId, replyWith, token)
        return token
    }

    /**
     * Unregister and cancel any pending question for a session. Called from the
     * [AgentService.startDelegatedSession] finally-block when the session ends.
     */
    fun unregisterSessionChannel(sessionId: String) {
        sessionChannels.remove(sessionId)?.pendingToken?.let { token ->
            token.armedQuestionId?.let { qid -> token.cancel(qid, "session_ended") }
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
            error("routeQuestion: a question is already pending on session $sessionId")
        }
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

    companion object {
        private val LOG = Logger.getInstance(DelegationInboundService::class.java)
    }
}

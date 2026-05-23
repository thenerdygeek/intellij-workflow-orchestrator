package com.workflow.orchestrator.agent.delegation

import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.delegation.ui.DelegationPicker
import com.workflow.orchestrator.agent.delegation.ui.PickerEntry
import com.workflow.orchestrator.core.delegation.DelegationClient
import com.workflow.orchestrator.core.delegation.DelegationFraming
import com.workflow.orchestrator.core.delegation.DelegationMessage
import com.workflow.orchestrator.core.delegation.DelegationPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.nio.channels.SocketChannel
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-project service. Owns the active outbound delegation channels (max 5)
 * for this IDE.
 *
 * Spec: docs/superpowers/specs/2026-05-22-cross-ide-agent-delegation-design.md §3.3, §4, §6.6.
 */
@Service(Service.Level.PROJECT)
class DelegationOutboundService(
    private val project: Project,
    private val cs: CoroutineScope,
) {
    private val activeChannels = ConcurrentHashMap<String, SocketChannel>()

    /** Maps outbound delegation handle → the local Agent-A session that holds it. */
    private val handleToSessionId = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * F1 fix: per-question text cache so [DelegationAnswerTool] can look up the
     * question text when the auto-approve setting is off (for the confirm dialog).
     *
     * Key: questionId → (handleId, questionText). Populated when a Question message
     * arrives; cleared when the answer is sent or the channel closes.
     */
    private val pendingQuestionTexts = java.util.concurrent.ConcurrentHashMap<String, Pair<String, String>>()

    private val json = Json { ignoreUnknownKeys = true; classDiscriminator = "type" }

    /**
     * F5: Serializes concurrent [send] calls so the 5-channel cap is atomic.
     *
     * Without this mutex two concurrent calls could both read [openChannelCount] == 4
     * and both proceed past the limit check, inserting two channels and breaching
     * MAX_CHANNELS. The mutex turns the check-then-insert into an atomic critical section.
     *
     * The picker dialog (an EDT suspension) runs inside the lock — this is acceptable
     * because we expect at most a few concurrent delegation_send calls per session, and
     * serializing them avoids the race entirely. The read-loop coroutine ([cs.launch])
     * is launched outside the lock so result delivery is not gated on the next send.
     */
    private val sendMutex = Mutex()

    /**
     * Epoch millis of the most recent IPC message received on each open channel.
     * Updated in the cs.launch reader loop on every framed message (any type,
     * including [DelegationMessage.Heartbeat]). Read by [IdleTimer] to decide
     * whether to fire [DelegationException.IdleTimedOut].
     *
     * Plan 3 spec §4.2.
     */
    private val lastSeenAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    val openChannelCount: Int get() = activeChannels.size

    /**
     * First-contact delegation: opens the picker, awaits user pick, opens IPC
     * channel, sends Connect, awaits AcceptResult. Returns the handle on Accept.
     *
     * Spawns a coroutine to read the Result from the channel; when Result arrives,
     * [onResult] is invoked. The channel is closed in the finally block.
     *
     * Serialized by [sendMutex] so the 5-channel cap check-then-insert is atomic
     * (F5 fix — spec §6.6).
     *
     * @throws DelegationException on the various failure modes.
     */
    suspend fun send(
        request: String,
        suggestedRepo: String?,
        delegatorSessionId: String,
        onResult: suspend (DelegationHandle, DelegationMessage.Result) -> Unit,
    ): DelegationHandle = sendMutex.withLock {
        if (openChannelCount >= MAX_CHANNELS) {
            throw DelegationException.LimitReached
        }
        val picked = pickTarget(suggestedRepo) ?: throw DelegationException.UserCanceledPicker
        val socketPath = DelegationPaths.socketFor(picked.path)
        val connect = DelegationMessage.Connect(
            delegatorIde = ideIdentifier(),
            delegatorRepo = project.name,
            delegatorSessionId = delegatorSessionId,
            request = request,
        )
        val pair = DelegationClient.connectAndAwaitAccept(socketPath, connect)
            ?: throw DelegationException.TargetNotReachable
        val (channel, ack) = pair
        if (!ack.accepted) {
            try { channel.close() } catch (_: Exception) {}
            throw DelegationException.Rejected(ack.reason)
        }
        val handle = DelegationHandle(
            id = UUID.randomUUID().toString(),
            targetProjectPath = picked.path.toString(),
            targetRepoName = picked.displayName,
        )
        activeChannels[handle.id] = channel
        lastSeenAt[handle.id] = System.currentTimeMillis()
        handleToSessionId[handle.id] = delegatorSessionId
        handleToRepoName[handle.id] = handle.targetRepoName
        // Note: the result-reader coroutine is launched OUTSIDE the sendMutex.withLock
        // body so that result delivery is not gated on the next send call. The channel
        // insertion into activeChannels above has already happened under the lock.
        cs.launch(Dispatchers.IO) {
            try {
                while (true) {
                    val msg = withContext(Dispatchers.IO) {
                        DelegationFraming.readFramed(channel, json)
                    }
                    lastSeenAt[handle.id] = System.currentTimeMillis()
                    when (msg) {
                        is DelegationMessage.Question -> handleIncomingQuestion(handle, msg)
                        is DelegationMessage.AnswerCanceled -> rescindLocalQuestion(handle, msg.questionId, msg.reason)
                        is DelegationMessage.Heartbeat -> {
                            // Liveness signal — already accounted for by the lastSeenAt update above.
                            // No further action needed at this point in the loop.
                        }
                        is DelegationMessage.Result -> {
                            onResult(handle, msg)
                            return@launch
                        }
                        else -> {
                            LOG.warn("Unexpected message on outbound channel: ${msg::class.simpleName}")
                        }
                    }
                }
            } catch (e: Exception) {
                LOG.warn("Result read failed for ${handle.id}", e)
                onResult(
                    handle,
                    DelegationMessage.Result(
                        status = DelegationMessage.ResultStatus.FAILED,
                        reason = "ipc_read_failed: ${e.message}",
                    )
                )
            } finally {
                close(handle.id)
            }
        }
        handle
    }

    /**
     * Closes the channel for [handleId]. Idempotent — no-op on unknown handle.
     * Returns true if a channel was found and closed.
     */
    fun close(handleId: String): Boolean {
        handleToSessionId.remove(handleId)
        handleToRepoName.remove(handleId)
        // F1 fix: also clear any pending question texts for this handle.
        pendingQuestionTexts.entries.removeIf { it.value.first == handleId }
        lastSeenAt.remove(handleId)
        val ch = activeChannels.remove(handleId) ?: return false
        try { ch.close() } catch (_: Exception) {}
        return true
    }

    /** Close every active channel. Called from project disposal. */
    fun closeAll() {
        activeChannels.keys.toList().forEach(::close)
    }

    /**
     * Cancel-cascade entry point. Closes every active channel whose
     * outbound handle belongs to [delegatorSessionId]. Returns the list of
     * closed handle IDs (mostly for logging / tests).
     *
     * Called from [com.workflow.orchestrator.agent.AgentService.cancelCurrentTask]
     * when the human cancels Agent-A's session. Each IDE-B receiver will detect
     * the socket close and surface a CANCELED state to its session UI.
     *
     * Plan 3 spec §5.3.
     */
    fun cancelAllForSession(delegatorSessionId: String, reason: String): List<String> {
        val toClose = handleToSessionId.entries
            .filter { it.value == delegatorSessionId }
            .map { it.key }
        for (h in toClose) {
            LOG.info("cancelAllForSession: closing handle $h (session=$delegatorSessionId, reason=$reason)")
            close(h)
        }
        return toClose
    }

    /**
     * Sends an Answer for a previously-received Question over the channel for [handleId].
     * Returns false if the handle is unknown or the channel is closed.
     */
    suspend fun sendAnswer(handleId: String, questionId: String, answer: String): Boolean {
        val channel = activeChannels[handleId] ?: return false
        return try {
            withContext(Dispatchers.IO) {
                DelegationFraming.writeFramed(
                    channel,
                    DelegationMessage.Answer(questionId = questionId, text = answer),
                    json,
                )
            }
            // F1 fix: clear the pending question text now that the answer is sent.
            pendingQuestionTexts.remove(questionId)
            true
        } catch (e: Exception) {
            LOG.warn("sendAnswer failed for $handleId / $questionId", e)
            false
        }
    }

    /**
     * Returns the text of a pending question sent over the given handle, or null if
     * the question has already been answered or the questionId is unknown.
     * Used by [com.workflow.orchestrator.agent.tools.delegation.DelegationAnswerTool]
     * to populate the confirm dialog when auto-approve is off.
     */
    fun lookupPendingQuestionText(handleId: String, questionId: String): String? =
        pendingQuestionTexts[questionId]?.takeIf { it.first == handleId }?.second

    /**
     * Returns the target repo display name for the given handle, or null if unknown.
     * Used by [com.workflow.orchestrator.agent.tools.delegation.DelegationAnswerTool]
     * to populate the confirm dialog title.
     */
    fun targetRepoName(handleId: String): String? {
        // handleToRepoName is populated in send() when the handle is created.
        return handleToRepoName[handleId]
    }

    private val handleToRepoName = java.util.concurrent.ConcurrentHashMap<String, String>()

    private suspend fun handleIncomingQuestion(
        handle: DelegationHandle,
        question: DelegationMessage.Question,
    ) {
        // F1 fix: cache the question text so delegation_answer can show it in the confirm dialog.
        pendingQuestionTexts[question.questionId] = handle.id to question.text

        val sessionId = handleToSessionId[handle.id] ?: run {
            LOG.warn("handleIncomingQuestion: no delegator session for ${handle.id}")
            return
        }
        val agentService = project.getService(
            com.workflow.orchestrator.agent.AgentService::class.java
        )
        // F6 fix: reorder guidance so "ask the user first if uncertain" is step 1
        // (matches spec §6.3) and "call delegation_answer directly" is step 2.
        // The previous text had them reversed, causing the LLM to default to
        // delegation_answer immediately rather than checking with the user.
        val nudge = buildString {
            append("Delegated session ${handle.targetRepoName} (handle ${handle.id.take(8)}) ")
            append("raised a clarifying question:\n\n")
            append(question.text)
            if (question.options.isNotEmpty()) {
                append("\n\nSuggested options: ${question.options.joinToString(", ")}")
            }
            append("\n\nQuestion ID: ${question.questionId}\n\n")
            append("How to respond:\n")
            append("1. If you cannot answer confidently from your own context, ask the user " +
                "first (via ask_followup_question), then use delegation_answer with the answer.\n")
            append("2. If you have enough context to answer correctly, call delegation_answer " +
                "directly with handle=${handle.id}, question_id=${question.questionId}, " +
                "and your answer text.")
        }
        agentService.enqueueNudgeForSession(sessionId, nudge)
    }

    private fun rescindLocalQuestion(handle: DelegationHandle, questionId: String, reason: String) {
        // F1 fix: remove the cached question text since it was answered locally (IDE-B side).
        pendingQuestionTexts.remove(questionId)
        val sessionId = handleToSessionId[handle.id] ?: return
        val agentService = project.getService(
            com.workflow.orchestrator.agent.AgentService::class.java
        )
        agentService.enqueueNudgeForSession(
            sessionId,
            "Delegated session question $questionId was answered locally by the " +
                "human in the receiving IDE ($reason). No action needed.",
        )
    }

    private suspend fun pickTarget(suggestedRepo: String?): PickerEntry? =
        withContext(Dispatchers.EDT) {
            val dlg = DelegationPicker(project, suggestedRepo)
            if (dlg.showAndGet()) dlg.selectedEntry else null
        }

    private fun ideIdentifier(): String {
        val pid = ProcessHandle.current().pid()
        return "ide-$pid"
    }

    /** Read accessor used by [IdleTimer] to evaluate stalls. Null if the handle is unknown. */
    fun lastSeenMillis(handleId: String): Long? = lastSeenAt[handleId]

    companion object {
        private val LOG = Logger.getInstance(DelegationOutboundService::class.java)
        const val MAX_CHANNELS = 5
    }
}

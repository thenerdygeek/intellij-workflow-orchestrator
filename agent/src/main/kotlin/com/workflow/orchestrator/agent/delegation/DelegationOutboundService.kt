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
        // Note: the result-reader coroutine is launched OUTSIDE the sendMutex.withLock
        // body so that result delivery is not gated on the next send call. The channel
        // insertion into activeChannels above has already happened under the lock.
        cs.launch(Dispatchers.IO) {
            try {
                val result = DelegationFraming.readFramed(channel, json) as? DelegationMessage.Result
                    ?: DelegationMessage.Result(
                        status = DelegationMessage.ResultStatus.FAILED,
                        reason = "unexpected non-Result message from peer",
                    )
                onResult(handle, result)
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
        val ch = activeChannels.remove(handleId) ?: return false
        try { ch.close() } catch (_: Exception) {}
        return true
    }

    /** Close every active channel. Called from project disposal. */
    fun closeAll() {
        activeChannels.keys.toList().forEach(::close)
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

    companion object {
        private val LOG = Logger.getInstance(DelegationOutboundService::class.java)
        const val MAX_CHANNELS = 5
    }
}

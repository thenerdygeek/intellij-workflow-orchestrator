package com.workflow.orchestrator.core.delegation

import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.SocketChannel
import java.nio.file.Path

/**
 * Outbound UDS client. Stateless utility — every call opens its own channel.
 *
 * Two convenience helpers:
 * - [ping] for one-shot liveness probes
 * - [connectAndAwaitAccept] for first-contact handshake
 *
 * For the longer-lived delegation channel (post-Accept), the caller holds the
 * returned [SocketChannel] directly and uses [DelegationFraming] for further
 * messages. Caller is responsible for closing the channel.
 *
 * Spec: docs/superpowers/specs/2026-05-22-cross-ide-agent-delegation-design.md §5.2 / §6.2.
 */
object DelegationClient {
    private val json = Json { ignoreUnknownKeys = true; classDiscriminator = "type" }
    private val LOG = Logger.getInstance(DelegationClient::class.java)

    /** Opens a fresh UDS channel. Caller owns the channel and must close it. */
    suspend fun openChannel(socketPath: Path): SocketChannel {
        val ch = SocketChannel.open(StandardProtocolFamily.UNIX)
        runInterruptible(Dispatchers.IO) {
            ch.connect(UnixDomainSocketAddress.of(socketPath))
        }
        return ch
    }

    /**
     * Liveness probe. Returns the Pong on success, null if the socket isn't bound
     * or the responder didn't reply within [timeoutMillis].
     *
     * Framing I/O is wrapped in [withContext](Dispatchers.IO) so callers that invoke
     * this from the EDT (e.g. [com.workflow.orchestrator.agent.delegation.ui.DelegationPicker]
     * via runBlockingCancellable) do not block the UI thread during socket reads/writes.
     *
     * F4: DelegationPicker EDT freeze fix (spec §5.2).
     */
    suspend fun ping(socketPath: Path, timeoutMillis: Long = 200): DelegationMessage.Pong? =
        withTimeoutOrNull(timeoutMillis) {
            var ch: SocketChannel? = null
            try {
                ch = openChannel(socketPath)
                withContext(Dispatchers.IO) {
                    DelegationFraming.writeFramed(
                        ch,
                        DelegationMessage.Ping(socketPath.toString()),
                        json,
                    )
                    DelegationFraming.readFramed(ch, json) as? DelegationMessage.Pong
                }
            } catch (e: Exception) {
                LOG.debug("ping failed for $socketPath", e)
                null
            } finally {
                try { ch?.close() } catch (_: Exception) {}
            }
        }

    /**
     * Sends a Connect and awaits the AcceptResult. Returns the channel still open
     * so the caller can hold it for further messages (Result + future Plan-2
     * questions). Caller must close the channel. Returns null if the target is
     * not reachable within [acceptTimeoutMillis].
     *
     * Framing I/O is wrapped in [withContext](Dispatchers.IO) to keep EDT callers
     * unblocked during the initial handshake write+read. The returned [SocketChannel]
     * is live — caller owns its subsequent I/O and close.
     *
     * F4: EDT-safe framing I/O (spec §6.2).
     */
    suspend fun connectAndAwaitAccept(
        socketPath: Path,
        connect: DelegationMessage.Connect,
        acceptTimeoutMillis: Long = 60_000,
    ): Pair<SocketChannel, DelegationMessage.AcceptResult>? = withTimeoutOrNull(acceptTimeoutMillis) {
        var ch: SocketChannel? = null
        try {
            ch = openChannel(socketPath)
            val ack = withContext(Dispatchers.IO) {
                DelegationFraming.writeFramed(ch, connect, json)
                DelegationFraming.readFramed(ch, json) as? DelegationMessage.AcceptResult
            } ?: run {
                try { ch.close() } catch (_: Exception) {}
                return@withTimeoutOrNull null
            }
            ch to ack
        } catch (e: Exception) {
            try { ch?.close() } catch (_: Exception) {}
            null
        }
    }
}

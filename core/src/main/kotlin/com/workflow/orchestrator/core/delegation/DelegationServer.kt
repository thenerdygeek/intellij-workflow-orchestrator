package com.workflow.orchestrator.core.delegation

import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path

/**
 * Inbound UDS listener for one open project. Binds [socketPath], accepts
 * connections, and dispatches Ping (auto-Pong) and Connect (forwarded to
 * [onConnect]) messages.
 *
 * Lifecycle:
 * - [start] binds and begins accepting in a coroutine launched on [scope].
 * - [stop] cancels the accept loop, closes the channel, deletes the socket file.
 *
 * Threading: blocking socket I/O is confined to [Dispatchers.IO] via
 * [withContext] and [runInterruptible]. The [onConnect] callback is invoked on
 * the [scope]'s own dispatcher so tests using a [kotlinx.coroutines.test.TestCoroutineScheduler]
 * can observe [CompletableDeferred] completions without needing real-time timeouts.
 *
 * Channel ownership: the [onConnect] handler receives both a [replyWith] suspend
 * closure and a [closeChannel] suspend closure. The caller may invoke [replyWith]
 * multiple times (first for AcceptResult, later for terminal Result). After sending
 * the final Result message the handler must invoke [closeChannel] so the per-connection
 * [SocketChannel] file-descriptor is released. Not calling [closeChannel] leaks the FD
 * until JVM exit.
 *
 * F1: socket leak fix — [closeChannel] replaces the prior "caller owns it" convention
 * that had no concrete close path after the terminal Result write. Spec §6.1.
 */
class DelegationServer(
    private val socketPath: Path,
    private val projectPath: String,
    private val onConnect: suspend (
        connect: DelegationMessage.Connect,
        replyWith: suspend (DelegationMessage) -> Unit,
        readMessage: suspend () -> DelegationMessage,
        closeChannel: suspend () -> Unit,
    ) -> Unit,
    /**
     * Handler invoked when an existing session is re-attached after IDE-A restart.
     *
     * Receives the [DelegationMessage.ChannelResume] first-message, a [replyWith]
     * suspend closure for sending the outcome reply ([DelegationMessage.ChannelResumed],
     * [DelegationMessage.SessionClosed], or [DelegationMessage.SessionNotFound]), a
     * [readMessage] suspend closure for reading subsequent frames (needed by H2 — the
     * resumed-channel reader loop on the inbound side), and a [closeChannel] suspend
     * closure to call when the channel is done (for non-resumed outcomes or when the
     * reader loop exits normally).
     *
     * For the Resumed outcome, the handler runs the read-loop and eventually calls
     * [closeChannel] when EOF or an exception terminates the loop. For SessionClosed /
     * SessionNotFound the handler calls [closeChannel] after the single reply.
     *
     * Defaults to a no-op so existing callers that don't need CHANNEL_RESUME handling
     * (e.g., non-inbound services, unit tests) don't need to pass the callback.
     *
     * Plan 4 spec §3.3, §4.1. H2 fix: added readMessage param so resumed channels
     * get a reader loop (spec §3.3 "restart the reader loop" requirement).
     */
    private val onChannelResume: suspend (
        resume: DelegationMessage.ChannelResume,
        replyWith: suspend (DelegationMessage) -> Unit,
        readMessage: suspend () -> DelegationMessage,
        closeChannel: suspend () -> Unit,
    ) -> Unit = { _, _, _, _ -> },
    private val scope: CoroutineScope,
) {
    private val json = Json { ignoreUnknownKeys = true; classDiscriminator = "type" }
    private var serverChannel: ServerSocketChannel? = null
    private var acceptJob: Job? = null

    fun start() {
        DelegationPaths.ensureIpcDir()
        Files.deleteIfExists(socketPath) // clean up stale file from prior crash
        val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        server.bind(UnixDomainSocketAddress.of(socketPath))
        serverChannel = server
        // Accept loop runs on Dispatchers.IO; per-connection handlers are children
        // of this job so structured cancellation works correctly.
        acceptJob = scope.launch(Dispatchers.IO) { acceptLoop(server) }
        LOG.info("DelegationServer bound at $socketPath for $projectPath")
    }

    fun stop() {
        acceptJob?.cancel()
        try { serverChannel?.close() } catch (_: Exception) { /* ignore */ }
        try { Files.deleteIfExists(socketPath) } catch (_: Exception) { /* ignore */ }
        LOG.info("DelegationServer stopped at $socketPath")
    }

    private suspend fun acceptLoop(server: ServerSocketChannel) {
        // coroutineScope {} creates a child scope whose Job is a child of acceptJob,
        // so all per-connection handlers participate in structured cancellation.
        // The accept loop itself is already on Dispatchers.IO (inherited from the launch).
        coroutineScope {
            while (isActive) {
                val client = try {
                    runInterruptible { server.accept() }
                } catch (e: Exception) {
                    if (isActive) LOG.warn("accept failed", e)
                    break
                }
                // Launch handler WITHOUT a dispatcher override so that the onConnect
                // callback runs on the scope's own dispatcher. Blocking socket I/O
                // inside handleConnection is wrapped in withContext(Dispatchers.IO).
                launch { handleConnection(client) }
            }
        }
    }

    private suspend fun handleConnection(client: SocketChannel) {
        try {
            val msg = withContext(Dispatchers.IO) { DelegationFraming.readFramed(client, json) }
            when (msg) {
                is DelegationMessage.Ping -> {
                    writeFramedTolerant(client, DelegationMessage.Pong(projectPath = projectPath), "Pong")
                    try { client.close() } catch (_: Exception) {}
                }
                is DelegationMessage.Connect -> {
                    // BUG #6 fix: per-connection write mutex serializes every framed write through
                    // the shared replyWith closure. Without this, concurrent producers — e.g.
                    // DelegationInboundService.routeQuestion (Question frame) and HeartbeatScheduler
                    // (Heartbeat tick) — can interleave their writes under send-buffer back-pressure,
                    // corrupting the framed stream and causing IDE-A to mis-decode a spurious FAILED
                    // result. Mirrors the OUTBOUND sendMutex pattern in DelegationOutboundService.
                    val writeMutex = Mutex()
                    val replyWith: suspend (DelegationMessage) -> Unit = { reply ->
                        writeMutex.withLock {
                            writeFramedTolerant(client, reply, reply::class.simpleName ?: "reply")
                        }
                    }
                    val readMessage: suspend () -> DelegationMessage = {
                        withContext(Dispatchers.IO) { DelegationFraming.readFramed(client, json) }
                    }
                    val closeChannel: suspend () -> Unit = {
                        try { client.close() } catch (_: Exception) {}
                    }
                    onConnect(msg, replyWith, readMessage, closeChannel)
                    // F1: the onConnect handler is now responsible for calling closeChannel()
                    // after the terminal Result is sent. The server does NOT close here because
                    // the handler may send multiple messages before the terminal one. The channel
                    // will also be closed (harmlessly/idempotently) by the catch block below on
                    // any exception path.
                }
                is DelegationMessage.ChannelResume -> {
                    // BUG #6 fix: same per-connection writeMutex for the ChannelResume path.
                    // The resumed-channel reader loop also drives replyWith concurrently with the
                    // HeartbeatScheduler, so it needs the same serialization guarantee.
                    val writeMutex = Mutex()
                    val replyWith: suspend (DelegationMessage) -> Unit = { reply ->
                        writeMutex.withLock {
                            writeFramedTolerant(client, reply, reply::class.simpleName ?: "reply")
                        }
                    }
                    val readMessage: suspend () -> DelegationMessage = {
                        withContext(Dispatchers.IO) { DelegationFraming.readFramed(client, json) }
                    }
                    val closeChannel: suspend () -> Unit = {
                        try { client.close() } catch (_: Exception) {}
                    }
                    onChannelResume(msg, replyWith, readMessage, closeChannel)
                    // The onChannelResume handler is responsible for calling closeChannel()
                    // for all outcomes — either immediately for SessionClosed/SessionNotFound,
                    // or after the resumed reader loop exits for the ChannelResumed case.
                }
                else -> {
                    LOG.warn("Unexpected first message on inbound connection: $msg")
                    try { client.close() } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            LOG.warn("connection handler failed", e)
            try { client.close() } catch (_: Exception) {}
        }
    }

    /**
     * Best-effort framed-reply delivery: writes [msg] on [Dispatchers.IO] and tolerates a
     * disconnected peer.
     *
     * If the write throws and [DelegationFraming.isPeerDisconnect] classifies it as a benign
     * "peer hung up, nothing to deliver" condition (IDE-A closed its end of the socket — closed
     * the handle / let a `wait` lapse / cancelled), it is logged at INFO and the call returns
     * normally. This is the root fix for the spurious `ClosedChannelException` that the
     * detached terminal-result delivery used to raise after a successful delegated session.
     *
     * Everything else propagates: notably [DelegationFraming.FrameSizeExceeded] (thrown BEFORE
     * the write loop — a genuine producer error) is NOT swallowed, and [CancellationException]
     * is always re-thrown so structured cancellation is never broken.
     */
    private suspend fun writeFramedTolerant(
        client: SocketChannel,
        msg: DelegationMessage,
        messageType: String,
    ) {
        try {
            withContext(Dispatchers.IO) { DelegationFraming.writeFramed(client, msg, json) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (DelegationFraming.isPeerDisconnect(e)) {
                LOG.info("peer disconnected before reply could be delivered; dropping $messageType")
            } else {
                throw e
            }
        }
    }

    companion object {
        private val LOG = Logger.getInstance(DelegationServer::class.java)
    }
}

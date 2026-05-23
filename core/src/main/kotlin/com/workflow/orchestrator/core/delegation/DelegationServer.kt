package com.workflow.orchestrator.core.delegation

import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.*
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
     * [DelegationMessage.SessionClosed], or [DelegationMessage.SessionNotFound]), and a
     * [closeChannel] suspend closure to call when the reply has been sent (for non-resumed
     * outcomes) or to leave open (for the resumed case, where the channel stays live for
     * further exchange).
     *
     * Defaults to a no-op so existing callers that don't need CHANNEL_RESUME handling
     * (e.g., non-inbound services, unit tests) don't need to pass the callback.
     *
     * Plan 4 spec §3.3, §4.1.
     */
    private val onChannelResume: suspend (
        resume: DelegationMessage.ChannelResume,
        replyWith: suspend (DelegationMessage) -> Unit,
        closeChannel: suspend () -> Unit,
    ) -> Unit = { _, _, _ -> },
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
                    withContext(Dispatchers.IO) {
                        DelegationFraming.writeFramed(
                            client,
                            DelegationMessage.Pong(projectPath = projectPath),
                            json,
                        )
                    }
                    try { client.close() } catch (_: Exception) {}
                }
                is DelegationMessage.Connect -> {
                    val replyWith: suspend (DelegationMessage) -> Unit = { reply ->
                        withContext(Dispatchers.IO) { DelegationFraming.writeFramed(client, reply, json) }
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
                    val replyWith: suspend (DelegationMessage) -> Unit = { reply ->
                        withContext(Dispatchers.IO) { DelegationFraming.writeFramed(client, reply, json) }
                    }
                    val closeChannel: suspend () -> Unit = {
                        try { client.close() } catch (_: Exception) {}
                    }
                    onChannelResume(msg, replyWith, closeChannel)
                    // The onChannelResume handler is responsible for calling closeChannel()
                    // for non-resumed outcomes (SessionClosed, SessionNotFound). For the
                    // Resumed outcome the channel is left open for ongoing exchange.
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

    companion object {
        private val LOG = Logger.getInstance(DelegationServer::class.java)
    }
}

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
 * Channel ownership: the [onConnect] handler receives a [replyWith] suspend
 * closure that captures the per-connection [SocketChannel]. Holding this
 * closure keeps the connection alive — the caller may invoke [replyWith]
 * multiple times (first for AcceptResult, later for Result) before letting
 * the connection close.
 *
 * Spec: docs/superpowers/specs/2026-05-22-cross-ide-agent-delegation-design.md §6.
 */
class DelegationServer(
    private val socketPath: Path,
    private val projectPath: String,
    private val onConnect: suspend (
        connect: DelegationMessage.Connect,
        replyWith: suspend (DelegationMessage) -> Unit,
    ) -> Unit,
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
                    onConnect(msg, replyWith)
                    // NOTE: caller may close client when done, or it may stay open for
                    // further messages. We deliberately do NOT close here.
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

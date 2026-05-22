@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.workflow.orchestrator.core.delegation

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.SocketChannel
import java.nio.file.Path

class DelegationServerTest {
    private val json = Json { ignoreUnknownKeys = true; classDiscriminator = "type" }

    @Test
    fun `server responds to Ping with Pong including project path`(@TempDir tmp: Path) = runTest {
        val socketPath = tmp.resolve("test.sock")
        coroutineScope {
            val server = DelegationServer(
                socketPath = socketPath,
                projectPath = "/users/me/frontend",
                onConnect = { _, _ -> error("Connect should not arrive during Ping test") },
                scope = this,
            )
            server.start()
            try {
                val client = SocketChannel.open(StandardProtocolFamily.UNIX).apply {
                    connect(UnixDomainSocketAddress.of(socketPath))
                }
                DelegationFraming.writeFramed(client, DelegationMessage.Ping("/users/me/frontend"), json)
                val response = DelegationFraming.readFramed(client, json) as DelegationMessage.Pong
                assertEquals("/users/me/frontend", response.projectPath)
                client.close()
            } finally {
                server.stop()
            }
        }
    }

    @Test
    fun `server delivers Connect to onConnect handler and replyWith forwards to client`(
        @TempDir tmp: Path,
    ) = runTest {
        val socketPath = tmp.resolve("test.sock")
        val connectSeen = CompletableDeferred<DelegationMessage.Connect>()
        coroutineScope {
            val server = DelegationServer(
                socketPath = socketPath,
                projectPath = "/users/me/frontend",
                onConnect = { connect, replyWith ->
                    connectSeen.complete(connect)
                    replyWith(DelegationMessage.AcceptResult(accepted = true))
                },
                scope = this,
            )
            server.start()
            try {
                val client = SocketChannel.open(StandardProtocolFamily.UNIX).apply {
                    connect(UnixDomainSocketAddress.of(socketPath))
                }
                DelegationFraming.writeFramed(
                    client,
                    DelegationMessage.Connect(
                        delegatorIde = "ide-A",
                        delegatorRepo = "backend",
                        delegatorSessionId = "sess1",
                        request = "do the thing",
                    ),
                    json,
                )
                // withContext(Dispatchers.Default.limitedParallelism(1)) opts out of
                // runTest's virtual-time clock so the real-thread completion of
                // connectSeen (from Dispatchers.IO) can be observed within the timeout.
                val seen = withContext(Dispatchers.Default.limitedParallelism(1)) {
                    withTimeout(2_000) { connectSeen.await() }
                }
                assertEquals("do the thing", seen.request)
                val ack = DelegationFraming.readFramed(client, json) as DelegationMessage.AcceptResult
                assertTrue(ack.accepted)
                client.close()
            } finally {
                server.stop()
            }
        }
    }

    @Test
    fun `stop deletes the socket file`(@TempDir tmp: Path) = runTest {
        val socketPath = tmp.resolve("delete.sock")
        coroutineScope {
            val server = DelegationServer(
                socketPath = socketPath,
                projectPath = "/p",
                onConnect = { _, _ -> },
                scope = this,
            )
            server.start()
            assertTrue(java.nio.file.Files.exists(socketPath))
            server.stop()
            assertFalse(java.nio.file.Files.exists(socketPath))
        }
    }

    @Test
    fun `start cleans up a stale socket file from a prior crash`(@TempDir tmp: Path) = runTest {
        val socketPath = tmp.resolve("stale.sock")
        // Simulate a stale file (a previous process left it behind)
        java.nio.file.Files.createFile(socketPath)
        coroutineScope {
            val server = DelegationServer(
                socketPath = socketPath,
                projectPath = "/p",
                onConnect = { _, _ -> },
                scope = this,
            )
            // Must not throw "Address already in use"
            server.start()
            server.stop()
        }
    }
}

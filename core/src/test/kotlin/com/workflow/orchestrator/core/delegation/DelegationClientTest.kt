package com.workflow.orchestrator.core.delegation

import kotlinx.coroutines.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class DelegationClientTest {

    @Test
    fun `ping returns Pong with project path when server is alive`(@TempDir tmp: Path) = runBlocking {
        val socketPath = tmp.resolve("alive.sock")
        coroutineScope {
            val server = DelegationServer(
                socketPath = socketPath,
                projectPath = "/projects/X",
                onConnect = { _, _ -> error("Connect should not arrive during Ping test") },
                scope = this,
            )
            server.start()
            try {
                val pong = withTimeout(2_000) { DelegationClient.ping(socketPath) }
                assertNotNull(pong)
                assertEquals("/projects/X", pong!!.projectPath)
            } finally {
                server.stop()
            }
        }
    }

    @Test
    fun `ping returns null when no server is bound`(@TempDir tmp: Path) = runBlocking {
        val socketPath = tmp.resolve("dead.sock")
        // Don't create or bind anything.
        val pong = DelegationClient.ping(socketPath, timeoutMillis = 200)
        assertNull(pong)
    }

    @Test
    fun `connectAndAwaitAccept happy path returns channel and ack`(@TempDir tmp: Path) = runBlocking {
        val socketPath = tmp.resolve("ok.sock")
        coroutineScope {
            val server = DelegationServer(
                socketPath = socketPath,
                projectPath = "/p",
                onConnect = { _, replyWith ->
                    replyWith(DelegationMessage.AcceptResult(accepted = true))
                },
                scope = this,
            )
            server.start()
            try {
                val pair = withTimeout(2_000) {
                    DelegationClient.connectAndAwaitAccept(
                        socketPath,
                        DelegationMessage.Connect("A", "b", "s", "go"),
                    )
                }
                assertNotNull(pair)
                val (ch, ack) = pair!!
                assertTrue(ack.accepted)
                ch.close()
            } finally {
                server.stop()
            }
        }
    }

    @Test
    fun `connectAndAwaitAccept rejects path returns ack with accepted=false`(@TempDir tmp: Path) = runBlocking {
        val socketPath = tmp.resolve("reject.sock")
        coroutineScope {
            val server = DelegationServer(
                socketPath = socketPath,
                projectPath = "/p",
                onConnect = { _, replyWith ->
                    replyWith(DelegationMessage.AcceptResult(accepted = false, reason = "user_rejected"))
                },
                scope = this,
            )
            server.start()
            try {
                val pair = withTimeout(2_000) {
                    DelegationClient.connectAndAwaitAccept(
                        socketPath,
                        DelegationMessage.Connect("A", "b", "s", "go"),
                    )
                }
                assertNotNull(pair)
                val (ch, ack) = pair!!
                assertFalse(ack.accepted)
                assertEquals("user_rejected", ack.reason)
                ch.close()
            } finally {
                server.stop()
            }
        }
    }

    @Test
    fun `connectAndAwaitAccept returns null when target is not reachable`(@TempDir tmp: Path) = runBlocking {
        val socketPath = tmp.resolve("nope.sock")
        val result = DelegationClient.connectAndAwaitAccept(
            socketPath,
            DelegationMessage.Connect("A", "b", "s", "go"),
            acceptTimeoutMillis = 200,
        )
        assertNull(result)
    }
}

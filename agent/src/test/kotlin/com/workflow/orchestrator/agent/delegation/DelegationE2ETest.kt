package com.workflow.orchestrator.agent.delegation

import com.workflow.orchestrator.core.delegation.DelegationClient
import com.workflow.orchestrator.core.delegation.DelegationFraming
import com.workflow.orchestrator.core.delegation.DelegationMessage
import com.workflow.orchestrator.core.delegation.DelegationServer
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class DelegationE2ETest {
    private val json = Json { ignoreUnknownKeys = true; classDiscriminator = "type" }

    @Test
    fun `happy path round-trips a delegation from A to B`(@TempDir tmp: Path) = runBlocking {
        val socketPath = tmp.resolve("e2e.sock")
        coroutineScope {
            // ---- Side B: server that auto-accepts and returns a COMPLETED Result ----
            val server = DelegationServer(
                socketPath = socketPath,
                projectPath = "/projects/B",
                onConnect = { connect, replyWith ->
                    assertEquals("backend-test", connect.delegatorRepo)
                    assertEquals("Add a createUser endpoint", connect.request)
                    replyWith(DelegationMessage.AcceptResult(accepted = true))
                    delay(50)  // simulate work
                    replyWith(
                        DelegationMessage.Result(
                            status = DelegationMessage.ResultStatus.COMPLETED,
                            summary = "Implemented createUser endpoint as requested.",
                            filesChanged = listOf("src/api/users.ts"),
                            branch = "feature/users-endpoint",
                            durationSeconds = 12,
                        )
                    )
                },
                scope = this,
            )
            server.start()
            try {
                // ---- Side A: connect, send, await ack + result ----
                val pair = DelegationClient.connectAndAwaitAccept(
                    socketPath,
                    DelegationMessage.Connect(
                        delegatorIde = "ide-A-test",
                        delegatorRepo = "backend-test",
                        delegatorSessionId = "sess-e2e-1",
                        request = "Add a createUser endpoint",
                    ),
                )
                assertNotNull(pair, "connectAndAwaitAccept must not return null on happy path")
                val (channel, ack) = pair!!
                assertTrue(ack.accepted)
                val result = withTimeout(2_000) {
                    DelegationFraming.readFramed(channel, json)
                } as DelegationMessage.Result
                assertEquals(DelegationMessage.ResultStatus.COMPLETED, result.status)
                assertEquals("Implemented createUser endpoint as requested.", result.summary)
                assertEquals(listOf("src/api/users.ts"), result.filesChanged)
                assertEquals("feature/users-endpoint", result.branch)
                assertEquals(12L, result.durationSeconds)
                channel.close()
            } finally {
                server.stop()
            }
        }
    }

    @Test
    fun `reject path returns AcceptResult with accepted=false and reason`(@TempDir tmp: Path) = runBlocking {
        val socketPath = tmp.resolve("reject.sock")
        coroutineScope {
            val server = DelegationServer(
                socketPath = socketPath,
                projectPath = "/projects/B",
                onConnect = { _, replyWith ->
                    replyWith(DelegationMessage.AcceptResult(accepted = false, reason = "user_rejected"))
                },
                scope = this,
            )
            server.start()
            try {
                val pair = DelegationClient.connectAndAwaitAccept(
                    socketPath,
                    DelegationMessage.Connect(
                        delegatorIde = "A",
                        delegatorRepo = "b",
                        delegatorSessionId = "s",
                        request = "go",
                    ),
                )
                assertNotNull(pair)
                val (channel, ack) = pair!!
                assertFalse(ack.accepted)
                assertEquals("user_rejected", ack.reason)
                channel.close()
            } finally {
                server.stop()
            }
        }
    }

    @Test
    fun `failure result round-trips intact`(@TempDir tmp: Path) = runBlocking {
        val socketPath = tmp.resolve("fail.sock")
        coroutineScope {
            val server = DelegationServer(
                socketPath = socketPath,
                projectPath = "/p",
                onConnect = { _, replyWith ->
                    replyWith(DelegationMessage.AcceptResult(accepted = true))
                    replyWith(
                        DelegationMessage.Result(
                            status = DelegationMessage.ResultStatus.FAILED,
                            reason = "tool_execution_failed: simulated",
                            durationSeconds = 3,
                        )
                    )
                },
                scope = this,
            )
            server.start()
            try {
                val (channel, _) = DelegationClient.connectAndAwaitAccept(
                    socketPath,
                    DelegationMessage.Connect("A", "b", "s", "go"),
                )!!
                val result = withTimeout(2_000) {
                    DelegationFraming.readFramed(channel, json)
                } as DelegationMessage.Result
                assertEquals(DelegationMessage.ResultStatus.FAILED, result.status)
                assertEquals("tool_execution_failed: simulated", result.reason)
                channel.close()
            } finally {
                server.stop()
            }
        }
    }

    @Test
    fun `ping unreachable target returns null`(@TempDir tmp: Path) = runBlocking {
        // No server bound — confirm DelegationClient.ping returns null cleanly
        val socketPath = tmp.resolve("noone.sock")
        val pong = DelegationClient.ping(socketPath, timeoutMillis = 200)
        assertNull(pong)
    }

    @Test
    fun `two parallel delegations on different sockets do not interfere`(@TempDir tmp: Path) = runBlocking {
        val socketA = tmp.resolve("a.sock")
        val socketB = tmp.resolve("b.sock")
        coroutineScope {
            val serverA = DelegationServer(
                socketPath = socketA,
                projectPath = "/A",
                onConnect = { c, replyWith ->
                    replyWith(DelegationMessage.AcceptResult(accepted = true))
                    replyWith(
                        DelegationMessage.Result(
                            status = DelegationMessage.ResultStatus.COMPLETED,
                            summary = "from-A:${c.request}",
                        )
                    )
                },
                scope = this,
            )
            val serverB = DelegationServer(
                socketPath = socketB,
                projectPath = "/B",
                onConnect = { c, replyWith ->
                    replyWith(DelegationMessage.AcceptResult(accepted = true))
                    replyWith(
                        DelegationMessage.Result(
                            status = DelegationMessage.ResultStatus.COMPLETED,
                            summary = "from-B:${c.request}",
                        )
                    )
                },
                scope = this,
            )
            serverA.start(); serverB.start()
            try {
                val results = listOf("A" to socketA, "B" to socketB).map { (tag, sock) ->
                    async {
                        val (ch, _) = DelegationClient.connectAndAwaitAccept(
                            sock,
                            DelegationMessage.Connect("ide", "r", "sess-$tag", "req-$tag"),
                        )!!
                        val r = withTimeout(2_000) { DelegationFraming.readFramed(ch, json) } as DelegationMessage.Result
                        ch.close()
                        r.summary
                    }
                }.awaitAll()
                assertEquals(listOf("from-A:req-A", "from-B:req-B"), results)
            } finally {
                serverA.stop(); serverB.stop()
            }
        }
    }
}

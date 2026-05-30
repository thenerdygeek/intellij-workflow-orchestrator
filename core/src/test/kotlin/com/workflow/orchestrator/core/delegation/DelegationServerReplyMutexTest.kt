@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.workflow.orchestrator.core.delegation

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path

/**
 * BUG #6 guard — inbound `replyWith` write serialization.
 *
 * Root cause: the `replyWith` closure built in [DelegationServer.handleConnection] for the
 * [DelegationMessage.Connect] and [DelegationMessage.ChannelResume] paths called [DelegationFraming.writeFramed]
 * with NO write serialization. Two producers — `DelegationInboundService.routeQuestion` (Question frame) and
 * `HeartbeatScheduler` (Heartbeat tick) — can call it concurrently on the SAME channel. If a partial write
 * (the inner `while (buf.hasRemaining())` loop iterates ≥2× under send-buffer back-pressure) occurs, the two
 * frames interleave on the wire; IDE-A's `readFramed` then mis-decodes and synthesizes a spurious FAILED result.
 *
 * Fix: a per-connection write [kotlinx.coroutines.sync.Mutex] (`writeMutex`) is created once in
 * `handleConnection`, captured by the `replyWith` closure, and every write is serialized under
 * `writeMutex.withLock { DelegationFraming.writeFramed(...) }`. This mirrors the OUTBOUND
 * `sendMutex` pattern in `DelegationOutboundService`.
 *
 * Tests:
 * 1. **Source-pin** — verifies the [DelegationServer] source contains a `writeMutex` and wraps
 *    `writeFramed` under `withLock`, covering ALL producers (Question, Heartbeat, Result, etc.) through
 *    the single shared closure.
 * 2. **Behavioral** — real UDS socket, concurrent `replyWith` calls from two coroutines on the same
 *    channel; asserts both frames are received and correctly decoded (no corruption / no interleaving).
 */
class DelegationServerReplyMutexTest {

    // ─────────────────────────────────────────────────────────────────────────
    // 1. Source-pin: DelegationServer declares writeMutex and uses withLock
    // ─────────────────────────────────────────────────────────────────────────

    private fun serverSource(): String =
        Files.readString(
            Path.of(
                "src/main/kotlin/com/workflow/orchestrator/core/delegation/DelegationServer.kt"
            )
        )

    @Test
    fun `BUG6 - DelegationServer declares a per-connection writeMutex for inbound replyWith serialization`() {
        val source = serverSource()
        assertTrue(
            source.contains("writeMutex"),
            "DelegationServer must declare a writeMutex per accepted connection to serialize inbound writeFramed calls"
        )
    }

    @Test
    fun `BUG6 - DelegationServer replyWith closure wraps writeFramed under writeMutex withLock`() {
        val source = serverSource()
        assertTrue(
            source.contains("writeMutex.withLock"),
            "DelegationServer.replyWith closure must hold writeMutex.withLock around every writeFramed call " +
                "so Question, Heartbeat, Result, ChannelResumed, etc. are all serialized"
        )
    }

    @Test
    fun `BUG6 - writeMutex import is present in DelegationServer`() {
        val source = serverSource()
        // The Mutex lives in kotlinx.coroutines.sync
        assertTrue(
            source.contains("kotlinx.coroutines.sync.Mutex") || source.contains("import kotlinx.coroutines.sync.*"),
            "DelegationServer must import kotlinx.coroutines.sync.Mutex to back writeMutex"
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. Behavioral: concurrent replyWith calls deliver both frames intact
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `BUG6 - concurrent replyWith calls on the same inbound channel deliver both frames without corruption`(
        @TempDir tmp: Path,
    ) = runTest {
        val socketPath = tmp.resolve("reply-mutex.sock")
        val json = kotlinx.serialization.json.Json {
            ignoreUnknownKeys = true
            classDiscriminator = "type"
        }

        // Gate: the test coroutine waits for both concurrent sends to be requested before
        // either fires, maximising the concurrency window.
        val bothReady = CompletableDeferred<Unit>()
        val sendCount = java.util.concurrent.atomic.AtomicInteger(0)

        coroutineScope {
            val server = DelegationServer(
                socketPath = socketPath,
                projectPath = "/test/project",
                onConnect = { _, replyWith, _, closeChannel ->
                    // Launch two concurrent "producers" on the same replyWith closure —
                    // exactly the race between routeQuestion and HeartbeatScheduler.
                    val job1 = launch {
                        if (sendCount.incrementAndGet() == 2) bothReady.complete(Unit)
                        else withContext(Dispatchers.Default) { withTimeout(2_000) { bothReady.await() } }
                        replyWith(DelegationMessage.AcceptResult(accepted = true, bSessionId = "producer-1"))
                    }
                    val job2 = launch {
                        if (sendCount.incrementAndGet() == 2) bothReady.complete(Unit)
                        else withContext(Dispatchers.Default) { withTimeout(2_000) { bothReady.await() } }
                        replyWith(DelegationMessage.AcceptResult(accepted = false, bSessionId = "producer-2"))
                    }
                    job1.join()
                    job2.join()
                    closeChannel()
                },
                scope = this,
            )
            server.start()
            try {
                val client = withContext(Dispatchers.IO) {
                    SocketChannel.open(StandardProtocolFamily.UNIX).apply {
                        connect(UnixDomainSocketAddress.of(socketPath))
                    }
                }
                // Send Connect to trigger the concurrent-producer scenario.
                withContext(Dispatchers.IO) {
                    DelegationFraming.writeFramed(
                        client,
                        DelegationMessage.Connect(
                            delegatorIde = "ide-A",
                            delegatorRepo = "test-repo",
                            delegatorSessionId = "sess-bugfix6",
                            request = "concurrency test",
                        ),
                        json,
                    )
                }

                // Read exactly two frames from the same channel.
                // Both must decode cleanly (no corrupt interleaving).
                val frames = withContext(Dispatchers.Default.limitedParallelism(1)) {
                    withTimeout(5_000) {
                        listOf(
                            withContext(Dispatchers.IO) { DelegationFraming.readFramed(client, json) },
                            withContext(Dispatchers.IO) { DelegationFraming.readFramed(client, json) },
                        )
                    }
                }
                client.close()

                // Both frames must be valid AcceptResult messages.
                assertEquals(2, frames.size, "exactly two frames must arrive, one per concurrent producer")
                assertTrue(
                    frames.all { it is DelegationMessage.AcceptResult },
                    "both frames must decode as AcceptResult — corrupt interleaving would throw or decode wrong type; " +
                        "got: ${frames.map { it::class.simpleName }}"
                )
                // The bSessionId values identify which producer sent which — both must be present.
                val bSessionIds = frames
                    .filterIsInstance<DelegationMessage.AcceptResult>()
                    .mapNotNull { it.bSessionId }
                    .toSet()
                assertEquals(
                    setOf("producer-1", "producer-2"),
                    bSessionIds,
                    "both producers must have delivered their distinct frames; got: $bSessionIds"
                )
            } finally {
                server.stop()
            }
        }
    }
}

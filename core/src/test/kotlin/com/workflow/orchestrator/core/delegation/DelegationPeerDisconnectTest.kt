package com.workflow.orchestrator.core.delegation

import kotlinx.coroutines.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.AsynchronousCloseException
import java.nio.channels.ClosedByInterruptException
import java.nio.channels.ClosedChannelException
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Path

/**
 * Guards the "peer hung up, nothing to deliver" benign-disconnect handling.
 *
 * Bug: a cross-IDE delegated session on IDE-B completes SUCCESSFULLY, but delivering the
 * terminal Result back to IDE-A throws [ClosedChannelException] because IDE-A already closed
 * its end of the socket (closed the handle / let a `wait` lapse / cancelled). The
 * [DelegationServer] reply boundary had no closed-peer guard so the benign condition escaped
 * as an error and the session was mislabeled FAILED.
 *
 * Fix:
 *  1. [DelegationFraming.isPeerDisconnect] classifies the benign closed/reset-peer throwables.
 *  2. The [DelegationServer] reply closures (Connect, ChannelResume, Ping) swallow a
 *     peer-disconnect on write and log it, but still propagate genuine errors (notably
 *     [DelegationFraming.FrameSizeExceeded], thrown BEFORE the write loop).
 *
 * These tests MUST fail before the fix (no [isPeerDisconnect]; reply path throws on a closed
 * peer) and pass after.
 */
class DelegationPeerDisconnectTest {

    private val json = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        classDiscriminator = "type"
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 1. Classification unit test — isPeerDisconnect predicate
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `isPeerDisconnect is true for ClosedChannelException and subclasses`() {
        assertTrue(DelegationFraming.isPeerDisconnect(ClosedChannelException()))
        assertTrue(DelegationFraming.isPeerDisconnect(AsynchronousCloseException()))
        assertTrue(DelegationFraming.isPeerDisconnect(ClosedByInterruptException()))
    }

    @Test
    fun `isPeerDisconnect is true for broken-pipe and connection-reset IOExceptions`() {
        assertTrue(DelegationFraming.isPeerDisconnect(IOException("Broken pipe")))
        assertTrue(DelegationFraming.isPeerDisconnect(IOException("broken pipe")))
        assertTrue(DelegationFraming.isPeerDisconnect(IOException("Connection reset by peer")))
        assertTrue(DelegationFraming.isPeerDisconnect(IOException("Socket closed")))
    }

    @Test
    fun `isPeerDisconnect is false for FrameSizeExceeded`() {
        assertFalse(
            DelegationFraming.isPeerDisconnect(DelegationFraming.FrameSizeExceeded(99_999_999)),
            "FrameSizeExceeded is a genuine producer error thrown BEFORE the write loop — must propagate",
        )
    }

    @Test
    fun `isPeerDisconnect is false for arbitrary RuntimeException and unrelated IOException`() {
        assertFalse(DelegationFraming.isPeerDisconnect(RuntimeException("boom")))
        assertFalse(DelegationFraming.isPeerDisconnect(IllegalStateException("nope")))
        assertFalse(DelegationFraming.isPeerDisconnect(IOException("disk full")))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. Real-socket: replyWith to a closed peer completes WITHOUT throwing
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `REAL-SOCKET - DelegationServer reply to a closed peer is benign (no throw)`(
        @TempDir tmp: Path,
    ) = runBlocking {
        val socketPath = tmp.resolve("peer-disconnect.sock")
        val replyOutcome = CompletableDeferred<Result<Unit>>()

        coroutineScope {
            val server = DelegationServer(
                socketPath = socketPath,
                projectPath = "/test/project",
                onConnect = { _, replyWith, _, closeChannel ->
                    // Give the client a moment to close its end first (it does so right
                    // after sending Connect). Then attempt the terminal-result reply.
                    withContext(Dispatchers.Default) { delay(200) }
                    val outcome = runCatching {
                        replyWith(
                            DelegationMessage.Result(
                                status = DelegationMessage.ResultStatus.COMPLETED,
                                summary = "done",
                                durationSeconds = 1,
                            ),
                        )
                    }
                    replyOutcome.complete(outcome)
                    runCatching { closeChannel() }
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
                withContext(Dispatchers.IO) {
                    DelegationFraming.writeFramed(
                        client,
                        DelegationMessage.Connect(
                            delegatorIde = "ide-A",
                            delegatorRepo = "repo",
                            delegatorSessionId = "sess-disc",
                            request = "do it",
                        ),
                        json,
                    )
                    // Simulate IDE-A hanging up: close our end before the reply arrives.
                    client.close()
                }

                val outcome = withTimeout(5_000) { replyOutcome.await() }
                assertTrue(
                    outcome.isSuccess,
                    "replyWith to a closed peer must complete benignly (no throw); " +
                        "got: ${outcome.exceptionOrNull()}",
                )
            } finally {
                server.stop()
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3. Real-socket: an oversized frame still throws FrameSizeExceeded
    //    (the benign guard must NOT swallow genuine producer errors)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `REAL-SOCKET - oversized frame still throws FrameSizeExceeded through the reply path`(
        @TempDir tmp: Path,
    ) = runBlocking {
        val socketPath = tmp.resolve("oversize.sock")
        val replyOutcome = CompletableDeferred<Result<Unit>>()

        coroutineScope {
            val server = DelegationServer(
                socketPath = socketPath,
                projectPath = "/test/project",
                onConnect = { _, replyWith, _, closeChannel ->
                    // Build a payload that blows past MAX_FRAME_BYTES so writeFramed throws
                    // FrameSizeExceeded BEFORE the write loop — a genuine error that the
                    // peer-disconnect guard must NOT swallow.
                    val huge = "Z".repeat(DelegationFraming.MAX_FRAME_BYTES + 1024)
                    val outcome = runCatching {
                        replyWith(
                            DelegationMessage.Result(
                                status = DelegationMessage.ResultStatus.COMPLETED,
                                summary = huge,
                                durationSeconds = 1,
                            ),
                        )
                    }
                    replyOutcome.complete(outcome)
                    runCatching { closeChannel() }
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
                withContext(Dispatchers.IO) {
                    DelegationFraming.writeFramed(
                        client,
                        DelegationMessage.Connect(
                            delegatorIde = "ide-A",
                            delegatorRepo = "repo",
                            delegatorSessionId = "sess-oversize",
                            request = "do it",
                        ),
                        json,
                    )
                }

                val outcome = withTimeout(5_000) { replyOutcome.await() }
                assertTrue(outcome.isFailure, "an oversized frame must surface an error, not be swallowed")
                assertTrue(
                    outcome.exceptionOrNull() is DelegationFraming.FrameSizeExceeded,
                    "the genuine error must be FrameSizeExceeded; got: ${outcome.exceptionOrNull()}",
                )
                client.close()
            } finally {
                server.stop()
            }
        }
    }
}

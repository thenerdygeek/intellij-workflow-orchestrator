package com.workflow.orchestrator.agent.delegation

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.delegation.DelegationFraming
import com.workflow.orchestrator.core.delegation.DelegationMessage
import com.workflow.orchestrator.core.delegation.DelegationServer
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path

/**
 * IDE-A side of the orphan-cancel fix: [DelegationOutboundService.close] (and therefore
 * [DelegationOutboundService.cancelAllForSession]) must send a [DelegationMessage.CancelTask]
 * frame BEFORE closing the socket when it is tearing down a STILL-RUNNING delegation — but must
 * NOT send one on a normal post-completion close (a terminal Result already recorded).
 *
 * Uses a real UDS [DelegationServer] on the IDE-B side that accepts a Connect and then reads
 * frames, capturing the first non-Connect message it sees.
 */
class DelegationCancelTaskSendTest {
    private val json = Json { ignoreUnknownKeys = true; classDiscriminator = "type" }

    @AfterEach fun tearDown() = unmockkAll()

    @Suppress("UNCHECKED_CAST")
    private fun <T> seedMap(svc: DelegationOutboundService, field: String, key: String, value: T) {
        val f = DelegationOutboundService::class.java.getDeclaredField(field).apply { isAccessible = true }
        (f.get(svc) as java.util.concurrent.ConcurrentHashMap<String, T>)[key] = value
    }

    private fun newOutbound(scope: CoroutineScope): DelegationOutboundService {
        val projectA = mockk<Project>(relaxed = true).also {
            every { it.name } returns "ide-a"
            every { it.basePath } returns null // persistHandlesForSession early-returns
            every { it.getService(any<Class<Any>>()) } answers { mockk(relaxed = true) }
        }
        return DelegationOutboundService(projectA, scope)
    }

    @Test
    fun `close on a running handle sends CancelTask before closing the socket`(@TempDir tmp: Path) = runBlocking {
        val socketPath = tmp.resolve("cancel-running.sock")
        val firstFrame = CompletableDeferred<DelegationMessage>()
        val serverScope = CoroutineScope(Job())
        val server = DelegationServer(
            socketPath = socketPath,
            projectPath = "/ide-b",
            onConnect = { _connect, replyWith, readMessage, closeChannel ->
                replyWith(DelegationMessage.AcceptResult(accepted = true, bSessionId = "b-sess-running"))
                try {
                    // Read frames until the channel closes; report the first one (the CancelTask).
                    while (true) {
                        val m = readMessage()
                        if (!firstFrame.isCompleted) firstFrame.complete(m)
                    }
                } catch (_: Exception) {
                    // EOF when IDE-A closes the socket after the CancelTask.
                } finally {
                    closeChannel()
                }
            },
            scope = serverScope,
        )
        server.start()
        val outScope = CoroutineScope(Job())
        val outbound = newOutbound(outScope)
        try {
            // IDE-A connects so we have a REAL live SocketChannel to seed into activeChannels.
            val pair = com.workflow.orchestrator.core.delegation.DelegationClient.connectAndAwaitAccept(
                socketPath,
                DelegationMessage.Connect("ide-a", "repo-a", "a-sess", "do work"),
            )!!
            val (channel, ack) = pair
            assertEquals("b-sess-running", ack.bSessionId)

            val handleId = "h-running"
            seedMap(outbound, "handleToSessionId", handleId, "a-sess")
            seedMap(outbound, "handleToBSessionId", handleId, "b-sess-running")
            seedMap(outbound, "handleToTargetPath", handleId, "/ide-b")
            seedMap(outbound, "handleToRepoName", handleId, "repo-a")
            // NO terminal state recorded → this is a live/running handle.
            seedMap<SocketChannel>(outbound, "activeChannels", handleId, channel)

            // The cancel under test (LLM `close` / UI Kill path).
            outbound.close(handleId, reason = "parent_canceled")

            val frame = withTimeout(3_000) { firstFrame.await() }
            assert(frame is DelegationMessage.CancelTask) { "expected CancelTask, got ${frame::class.simpleName}" }
            val cancel = frame as DelegationMessage.CancelTask
            assertEquals("b-sess-running", cancel.sessionId, "CancelTask must target IDE-B's session id")
            assertEquals("parent_canceled", cancel.reason)
        } finally {
            server.stop(); serverScope.cancel(); outScope.cancel()
            runCatching { Files.deleteIfExists(socketPath) }
        }
        Unit
    }

    @Test
    fun `close on an already-terminal handle does NOT send CancelTask`(@TempDir tmp: Path) = runBlocking {
        val socketPath = tmp.resolve("cancel-terminal.sock")
        val firstFrame = CompletableDeferred<DelegationMessage>()
        val serverScope = CoroutineScope(Job())
        val server = DelegationServer(
            socketPath = socketPath,
            projectPath = "/ide-b",
            onConnect = { _connect, replyWith, readMessage, closeChannel ->
                replyWith(DelegationMessage.AcceptResult(accepted = true, bSessionId = "b-sess-done"))
                try {
                    while (true) {
                        val m = readMessage()
                        if (!firstFrame.isCompleted) firstFrame.complete(m)
                    }
                } catch (_: Exception) {
                } finally {
                    closeChannel()
                }
            },
            scope = serverScope,
        )
        server.start()
        val outScope = CoroutineScope(Job())
        val outbound = newOutbound(outScope)
        try {
            val (channel, _) = com.workflow.orchestrator.core.delegation.DelegationClient.connectAndAwaitAccept(
                socketPath,
                DelegationMessage.Connect("ide-a", "repo-a", "a-sess", "do work"),
            )!!

            val handleId = "h-done"
            seedMap(outbound, "handleToSessionId", handleId, "a-sess")
            seedMap(outbound, "handleToBSessionId", handleId, "b-sess-done")
            seedMap(outbound, "handleToTargetPath", handleId, "/ide-b")
            seedMap(outbound, "handleToRepoName", handleId, "repo-a")
            // Terminal state recorded → normal post-completion close, NOT a cancel.
            seedMap(outbound, "handleToLastSeenState", handleId, DelegationMessage.ResultStatus.COMPLETED.name)
            seedMap<SocketChannel>(outbound, "activeChannels", handleId, channel)

            outbound.close(handleId)

            // The server side should observe EOF (socket close), NOT a CancelTask frame.
            // Give the write path a moment; firstFrame must remain uncompleted.
            val frame = kotlinx.coroutines.withTimeoutOrNull(800) { firstFrame.await() }
            assertNull(frame, "no CancelTask must be sent on a normal post-completion close; got $frame")
        } finally {
            server.stop(); serverScope.cancel(); outScope.cancel()
            runCatching { Files.deleteIfExists(socketPath) }
        }
        Unit
    }
}

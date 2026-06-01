package com.workflow.orchestrator.agent.delegation

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.delegation.DelegationMessage
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path

/**
 * Coverage for the explicit `delegation(action="wait")` attach path
 * ([DelegationOutboundService.awaitResult]).
 */
class DelegationWaitTest {

    @AfterEach fun tearDown() { unmockkAll() }

    private fun newService(): DelegationOutboundService {
        val project = mockk<Project>(relaxed = true)
        return DelegationOutboundService(project, CoroutineScope(SupervisorJob()))
    }

    private fun seedMap(svc: DelegationOutboundService, field: String, key: String, value: String) {
        val f = DelegationOutboundService::class.java.getDeclaredField(field).apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (f.get(svc) as java.util.concurrent.ConcurrentHashMap<String, String>)[key] = value
    }

    private fun seedChannel(svc: DelegationOutboundService, handleId: String) {
        val f = DelegationOutboundService::class.java.getDeclaredField("activeChannels").apply { isAccessible = true }
        // The mock must emulate a BLOCKING SocketChannel write: a relaxed mock's write() returns 0,
        // which makes DelegationFraming.writeFramed's `while (buf.hasRemaining()) channel.write(buf)`
        // spin forever (and MockK records every invocation → OOM). Since close() now best-effort
        // writes a CancelTask frame on a live channel, the mock must consume the buffer like a real
        // blocking channel does.
        val ch = mockk<SocketChannel>(relaxed = true)
        every { ch.write(any<java.nio.ByteBuffer>()) } answers {
            val buf = firstArg<java.nio.ByteBuffer>()
            val n = buf.remaining()
            buf.position(buf.limit())
            n
        }
        @Suppress("UNCHECKED_CAST")
        (f.get(svc) as java.util.concurrent.ConcurrentHashMap<String, SocketChannel>)[handleId] = ch
    }

    @Suppress("UNCHECKED_CAST")
    private fun waiterFor(svc: DelegationOutboundService, handleId: String): CompletableDeferred<DelegationWaitOutcome>? {
        val f = DelegationOutboundService::class.java.getDeclaredField("pendingResultWaiters").apply { isAccessible = true }
        return (f.get(svc) as java.util.concurrent.ConcurrentHashMap<String, CompletableDeferred<DelegationWaitOutcome>>)[handleId]
    }

    @Test
    fun `awaitResult times out while the delegation is still running`() {
        val svc = newService()
        seedChannel(svc, "h")
        seedMap(svc, "handleToRepoName", "h", "backend")
        val outcome = runBlocking { svc.awaitResult("h", 60L) }
        val t = assertInstanceOf(DelegationWaitOutcome.TimedOut::class.java, outcome)
        assertEquals("backend", t.repoName)
    }

    @Test
    fun `awaitResult returns NotActive(handle_not_found) for an unknown handle`() {
        val svc = newService()
        val outcome = runBlocking { svc.awaitResult("ghost", 60L) }
        val na = assertInstanceOf(DelegationWaitOutcome.NotActive::class.java, outcome)
        assertEquals("handle_not_found", na.reason)
    }

    @Test
    fun `awaitResult returns NotActive(already_completed) once the handle has closed`() {
        val svc = newService()
        // Seed enough for close() to create a retained snapshot, then close.
        seedMap(svc, "handleToBSessionId", "h", "sess-b")
        seedMap(svc, "handleToTargetPath", "h", "/x")
        svc.close("h")
        val outcome = runBlocking { svc.awaitResult("h", 60L) }
        val na = assertInstanceOf(DelegationWaitOutcome.NotActive::class.java, outcome)
        assertEquals("already_completed", na.reason)
    }

    @Test
    fun `awaitResult returns NotActive(already_completed) when the retained terminal state is COMPLETED`() {
        val svc = newService()
        seedMap(svc, "handleToBSessionId", "h", "sess-b")
        seedMap(svc, "handleToTargetPath", "h", "/x")
        // The reader records the real terminal Result into handleToLastSeenState BEFORE close().
        seedMap(svc, "handleToLastSeenState", "h", "COMPLETED")
        svc.close("h")
        val outcome = runBlocking { svc.awaitResult("h", 60L) }
        val na = assertInstanceOf(DelegationWaitOutcome.NotActive::class.java, outcome)
        assertEquals("already_completed", na.reason)
    }

    @Test
    fun `awaitResult returns NotActive(already_canceled) for a CANCELED retained handle`() {
        val svc = newService()
        // A live-running channel that is intentionally closed → close() seeds lastState=CANCELED.
        seedChannel(svc, "h")
        seedMap(svc, "handleToBSessionId", "h", "sess-b")
        seedMap(svc, "handleToTargetPath", "h", "/x")
        svc.close("h")
        val outcome = runBlocking { svc.awaitResult("h", 60L) }
        val na = assertInstanceOf(DelegationWaitOutcome.NotActive::class.java, outcome)
        assertEquals("already_canceled", na.reason)
    }

    @Test
    fun `awaitResult returns NotActive(already_failed) for a FAILED retained handle`() {
        val svc = newService()
        seedMap(svc, "handleToBSessionId", "h", "sess-b")
        seedMap(svc, "handleToTargetPath", "h", "/x")
        seedMap(svc, "handleToLastSeenState", "h", "FAILED")
        svc.close("h")
        val outcome = runBlocking { svc.awaitResult("h", 60L) }
        val na = assertInstanceOf(DelegationWaitOutcome.NotActive::class.java, outcome)
        assertEquals("already_failed", na.reason)
    }

    @Test
    fun `awaitResult returns NotActive(already_rejected) for a REJECTED retained handle`() {
        val svc = newService()
        seedMap(svc, "handleToBSessionId", "h", "sess-b")
        seedMap(svc, "handleToTargetPath", "h", "/x")
        seedMap(svc, "handleToLastSeenState", "h", "REJECTED")
        svc.close("h")
        val outcome = runBlocking { svc.awaitResult("h", 60L) }
        val na = assertInstanceOf(DelegationWaitOutcome.NotActive::class.java, outcome)
        assertEquals("already_rejected", na.reason)
    }

    @Test
    fun `awaitResult returns the result when it arrives while waiting`() {
        val svc = newService()
        seedChannel(svc, "h")
        seedMap(svc, "handleToRepoName", "h", "frontend")
        val outcome = runBlocking {
            val job = async { svc.awaitResult("h", 5_000L) }
            // Wait until awaitResult has registered its waiter, then complete it (as the reader loop would).
            var w = waiterFor(svc, "h")
            while (w == null) { delay(5); w = waiterFor(svc, "h") }
            w.complete(
                DelegationWaitOutcome.Completed(
                    DelegationMessage.Result(status = DelegationMessage.ResultStatus.COMPLETED, summary = "all done"),
                    "frontend",
                )
            )
            job.await()
        }
        val c = assertInstanceOf(DelegationWaitOutcome.Completed::class.java, outcome)
        assertEquals("all done", c.result.summary)
        assertEquals("frontend", c.repoName)
    }

    @Test
    fun `source pin - reader loop completes a pending waiter for Result and Question`() {
        val src = Files.readString(
            Path.of("src/main/kotlin/com/workflow/orchestrator/agent/delegation/DelegationOutboundService.kt")
        )
        // Both branches must consult pendingResultWaiters so a blocking wait() gets the
        // result/question inline instead of it going only to the async nudge path.
        assertTrue(src.contains("pendingResultWaiters.remove(handle.id)"),
            "reader loop must complete a pending waiter on Result/Question")
        assertTrue(src.contains("DelegationWaitOutcome.Completed") && src.contains("DelegationWaitOutcome.Question"),
            "reader loop must complete waiters with Completed and Question outcomes")
    }
}

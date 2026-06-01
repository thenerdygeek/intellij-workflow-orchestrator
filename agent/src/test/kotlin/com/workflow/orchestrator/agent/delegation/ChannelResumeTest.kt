package com.workflow.orchestrator.agent.delegation

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.testutil.installReadActionInlineShim
import com.workflow.orchestrator.core.delegation.DelegationMessage
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class ChannelResumeTest {

    @BeforeEach fun setup() { installReadActionInlineShim() }
    @AfterEach fun tearDown() { unmockkAll() }

    @Test
    fun `loadPersistedHandles rehydrates from disk into dead entries`(@TempDir tmp: Path) {
        val store = PersistentHandleStore(sessionDir = tmp)
        store.save(listOf(
            PersistentHandleEntry(
                handleId = "h-persisted",
                targetProjectPath = "/repo/b",
                targetRepoName = "frontend",
                bSessionId = "sess-b",
                lastSeenState = "RUNNING",
                createdAt = 1716480000000L,
            )
        ))

        val project = mockk<Project>(relaxed = true)
        every { project.messageBus } returns mockk(relaxed = true)
        val cs = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob())
        val service = DelegationOutboundService(project, cs)

        service.loadPersistedHandles(sessionDir = tmp, delegatorSessionId = "sess-a")

        // After load, sendContinuation on the persisted handle should attempt
        // resume rather than throwing handle_not_found.
        val ex = assertThrows(DelegationException.Expired::class.java) {
            kotlinx.coroutines.runBlocking {
                service.sendContinuation(
                    handleId = "h-persisted",
                    request = "test",
                    delegatorSessionId = "sess-a",
                )
            }
        }
        // After Task 3, sendContinuation on a dead handle triggers attemptResume.
        // With no testResumeProbe stub here, the production probe path runs and fails
        // (no live IDE-B). Expect a non-trivial reason (not just handle_not_found).
        assertTrue(
            ex.expireReason?.contains("ide_b_not_running") == true
                || ex.expireReason?.contains("io_error") == true
                || ex.expireReason?.contains("not_found") == true,
            "Expected resume-probe failure reason, got: ${ex.expireReason}"
        )
    }

    @Test
    fun `attemptResume returns NotFound when probe succeeds but session unknown`(@TempDir tmp: Path) {
        val project = mockk<Project>(relaxed = true)
        every { project.messageBus } returns mockk(relaxed = true)
        val cs = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob())
        val service = DelegationOutboundService(project, cs)

        service.testResumeProbe = { _, _ -> DelegationMessage.SessionNotFound(sessionId = "sess-gone") }
        injectPersistedHandle(service, "h-gone", "sess-gone")

        val outcome = kotlinx.coroutines.runBlocking { service.attemptResume("h-gone") }
        assertEquals(ResumeOutcome.NotFound, outcome)
    }

    @Test
    fun `attemptResume returns Closed with summary on SessionClosed`(@TempDir tmp: Path) {
        val project = mockk<Project>(relaxed = true)
        every { project.messageBus } returns mockk(relaxed = true)
        val cs = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob())
        val service = DelegationOutboundService(project, cs)

        service.testResumeProbe = { _, _ ->
            DelegationMessage.SessionClosed(
                sessionId = "sess-done",
                closeReason = "completed",
                summary = "All work done.",
            )
        }
        injectPersistedHandle(service, "h-done", "sess-done")

        val outcome = kotlinx.coroutines.runBlocking { service.attemptResume("h-done") }
        assertTrue(outcome is ResumeOutcome.Closed)
        outcome as ResumeOutcome.Closed
        assertEquals("completed", outcome.closeReason)
        assertEquals("All work done.", outcome.summary)
    }

    @Test
    fun `attemptResume returns ProbeFailed when ping returns null`(@TempDir tmp: Path) {
        val project = mockk<Project>(relaxed = true)
        every { project.messageBus } returns mockk(relaxed = true)
        val cs = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob())
        val service = DelegationOutboundService(project, cs)

        service.testResumeProbe = { _, _ -> null }  // simulate refused / no PONG
        injectPersistedHandle(service, "h-no-ide", "sess-x")

        val outcome = kotlinx.coroutines.runBlocking { service.attemptResume("h-no-ide") }
        assertTrue(outcome is ResumeOutcome.ProbeFailed,
            "Expected ProbeFailed, got $outcome")
    }

    @Test
    fun `attemptResume returns Resumed on ChannelResumed reply`(@TempDir tmp: Path) {
        val project = mockk<Project>(relaxed = true)
        every { project.messageBus } returns mockk(relaxed = true)
        val cs = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob())
        val service = DelegationOutboundService(project, cs)

        service.testResumeProbe = { _, _ ->
            DelegationMessage.ChannelResumed(sessionId = "sess-live", currentState = "AWAITING_ANSWER")
        }
        injectPersistedHandle(service, "h-live", "sess-live")

        val outcome = kotlinx.coroutines.runBlocking { service.attemptResume("h-live") }
        assertTrue(outcome is ResumeOutcome.Resumed)
        outcome as ResumeOutcome.Resumed
        assertEquals("AWAITING_ANSWER", outcome.currentState)
    }

    // ── Fix B (.23 #5): continuation busy → Rejected (retryable), terminal-gone → Expired ──

    @Test
    fun `sendContinuation to a busy target throws Rejected not Expired`(@TempDir tmp: Path) {
        val project = mockk<Project>(relaxed = true)
        every { project.messageBus } returns mockk(relaxed = true)
        val cs = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob())
        val service = DelegationOutboundService(project, cs)

        // IDE-B is alive but its agent tab is occupied → SessionClosed(ide_b_busy …).
        service.testResumeProbe = { _, _ ->
            DelegationMessage.SessionClosed(
                sessionId = "sess-busy",
                closeReason = "ide_b_busy: agent tab is running another task; could not resume",
                summary = null,
            )
        }
        injectPersistedHandle(service, "h-busy", "sess-busy")

        val ex = assertThrows(DelegationException.Rejected::class.java) {
            kotlinx.coroutines.runBlocking {
                service.sendContinuation(handleId = "h-busy", request = "follow up", delegatorSessionId = "sess-a")
            }
        }
        assertTrue(ex.rejectReason?.contains("ide_b_busy") == true,
            "Rejected reason must carry ide_b_busy, got: ${ex.rejectReason}")
    }

    @Test
    fun `sendContinuation to a genuinely-closed session throws Expired not Rejected`(@TempDir tmp: Path) {
        val project = mockk<Project>(relaxed = true)
        every { project.messageBus } returns mockk(relaxed = true)
        val cs = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob())
        val service = DelegationOutboundService(project, cs)

        // Genuinely-gone terminal close (NOT busy) → still Expired (handle no longer usable).
        service.testResumeProbe = { _, _ ->
            DelegationMessage.SessionClosed(
                sessionId = "sess-done",
                closeReason = "completed",
                summary = "All work done.",
            )
        }
        injectPersistedHandle(service, "h-done", "sess-done")

        val ex = assertThrows(DelegationException.Expired::class.java) {
            kotlinx.coroutines.runBlocking {
                service.sendContinuation(handleId = "h-done", request = "follow up", delegatorSessionId = "sess-a")
            }
        }
        assertTrue(ex.expireReason?.contains("session_closed") == true,
            "Expired reason must carry session_closed for a terminal close, got: ${ex.expireReason}")
    }

    private fun injectPersistedHandle(service: DelegationOutboundService, handleId: String, bSessionId: String) {
        // Use reflection to seed the internal maps a load would have populated.
        // attemptResume returns NotFound early if any of these are missing — seed all four.
        seedMap(service, "handleToBSessionId", handleId, bSessionId)
        seedMap(service, "handleToSessionId", handleId, "sess-a")
        seedMap(service, "handleToTargetPath", handleId, "/tmp/test-target")
        seedMap(service, "handleToLastSeenState", handleId, "RUNNING")
    }

    @Suppress("UNCHECKED_CAST")
    private fun seedMap(service: DelegationOutboundService, fieldName: String, key: String, value: String) {
        val f = DelegationOutboundService::class.java.getDeclaredField(fieldName).apply { isAccessible = true }
        (f.get(service) as java.util.concurrent.ConcurrentHashMap<String, String>)[key] = value
    }
}

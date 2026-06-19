package com.workflow.orchestrator.agent.monitor

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.loop.AgentLoop
import com.workflow.orchestrator.agent.loop.queue.QueuedMessage
import com.workflow.orchestrator.agent.settings.AgentSettings
import com.workflow.orchestrator.agent.tools.background.IdleSessionWaker
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Behavioral characterization of [AgentMonitorCoordinator], the Phase 3 extraction of the
 * monitor cluster out of `AgentService`. The cluster was previously un-unit-testable because
 * it lived on a `@Service(PROJECT)` god-class that can't be instantiated without the full
 * platform; extracting it into this plain injectable class makes the behavior testable for the
 * first time.
 *
 * These tests pin the seam value of the extraction:
 *  - the coordinator owns [MonitorPersistence], built lazily from the injected `agentDirProvider`
 *    (so persisted monitor notifications round-trip through the directory the coordinator is told
 *    about — the lateinit-`agentDir` hazard the provider lambda solves);
 *  - `disposeMonitorsForSession` drives `MonitorPool.killAll` for the session;
 *  - the per-session no-op methods are safe to call for a session that never created a manager.
 */
class AgentMonitorCoordinatorTest {

    @TempDir
    lateinit var agentDir: Path

    private val project: Project = mockk(relaxed = true)
    private val pool: MonitorPool = mockk(relaxed = true)
    private val idleWaker: IdleSessionWaker = mockk(relaxed = true)
    private lateinit var cs: CoroutineScope

    /** A persistence handle over the SAME dir the coordinator is given, used to seed/inspect state. */
    private lateinit var seed: MonitorPersistence

    @BeforeEach
    fun setup() {
        cs = CoroutineScope(Dispatchers.IO + SupervisorJob())
        every { project.getService(MonitorPool::class.java) } returns pool
        seed = MonitorPersistence(agentDir)
        mockkObject(AgentSettings.Companion)
        every { AgentSettings.getInstance(any()) } returns mockk {
            every { state } returns AgentSettings.State()
        }
    }

    @AfterEach
    fun teardown() {
        MonitorBridge.clearRouter(project)
        cs.cancel()
        unmockkObject(AgentSettings.Companion)
        unmockkAll()
    }

    private fun buildCoordinator(
        activeLoopForSession: (String) -> AgentLoop? = { null },
        enqueueToQueue: (String, QueuedMessage) -> Unit = { _, _ -> },
    ): AgentMonitorCoordinator = AgentMonitorCoordinator(
        project = project,
        cs = cs,
        agentDirProvider = { agentDir.toFile() },
        idleWaker = idleWaker,
        activeLoopForSession = activeLoopForSession,
        enqueueToQueue = enqueueToQueue,
    )

    /** Seed a legacy monitor-notifications.json directly (the writer was removed in Task 2.4). */
    private fun seedLegacyNotifications(sessionId: String, vararg texts: String) {
        val sessionDir = agentDir.resolve("sessions").resolve(sessionId)
        Files.createDirectories(sessionDir)
        val json = texts.joinToString(separator = "\",\"", prefix = "[\"", postfix = "\"]")
        Files.writeString(sessionDir.resolve("monitor-notifications.json"), json)
    }

    @Test
    fun `loadPendingNotifications reads legacy notifications from the injected agentDir`() {
        // Seed the legacy file directly (appendPendingNotification writer removed in Task 2.4)
        seedLegacyNotifications("s1", "note-A", "note-B")

        val coordinator = buildCoordinator()

        assertEquals(listOf("note-A", "note-B"), coordinator.loadPendingNotifications("s1"))
    }

    @Test
    fun `clearPendingNotifications deletes the legacy notifications file`() {
        seedLegacyNotifications("s1", "note-A")
        val coordinator = buildCoordinator()

        coordinator.clearPendingNotifications("s1")

        assertTrue(coordinator.loadPendingNotifications("s1").isEmpty())
    }

    @Test
    fun `clearPersistedMonitors also clears legacy pending notifications file`() {
        seedLegacyNotifications("s2", "stale")
        val coordinator = buildCoordinator()

        coordinator.clearPersistedMonitors("s2")

        assertTrue(seed.loadPendingNotifications("s2").isEmpty())
    }

    @Test
    fun `disposeMonitorsForSession kills the session's pool monitors`() {
        val coordinator = buildCoordinator()

        coordinator.disposeMonitorsForSession("s3")

        verify { pool.killAll("s3") }
    }

    @Test
    fun `forgetMonitor and markMonitorsDormantForSession are safe no-ops for an unknown session`() {
        val coordinator = buildCoordinator()

        // Neither touches AgentSettings / constructs a manager: both are null-safe map lookups
        // for a session that never created one (unlike ensureMonitorManager, which builds one).
        assertDoesNotThrow {
            coordinator.forgetMonitor("unknown-session", "some-id")
            coordinator.markMonitorsDormantForSession("unknown-session")
        }
    }

    @Test
    fun `flush loop is NOT running after construction`() {
        val coordinator = buildCoordinator()
        assertFalse(coordinator.isFlushLoopRunning())
    }

    @Test
    fun `flush loop starts on first ensureMonitorManager`() {
        val coordinator = buildCoordinator()
        coordinator.ensureMonitorManager("session-1")
        assertTrue(coordinator.isFlushLoopRunning())
    }

    @Test
    fun `flush loop stops when the last session's monitors are disposed`() {
        val coordinator = buildCoordinator()
        coordinator.ensureMonitorManager("session-1")
        coordinator.ensureMonitorManager("session-2")
        coordinator.disposeMonitorsForSession("session-1")
        assertTrue(coordinator.isFlushLoopRunning(), "one session still has monitors")
        coordinator.disposeMonitorsForSession("session-2")
        assertFalse(coordinator.isFlushLoopRunning())
    }
}

package com.workflow.orchestrator.agent.monitor

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.loop.AgentLoop
import com.workflow.orchestrator.agent.loop.queue.QueuedMessage
import com.workflow.orchestrator.agent.session.AsyncEventCardData
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
import kotlinx.serialization.json.Json
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
        emitCard: (String, AsyncEventCardData) -> Unit = { _, _ -> },
    ): AgentMonitorCoordinator = AgentMonitorCoordinator(
        project = project,
        cs = cs,
        agentDirProvider = { agentDir.toFile() },
        idleWaker = idleWaker,
        activeLoopForSession = activeLoopForSession,
        enqueueToQueue = enqueueToQueue,
        emitCard = emitCard,
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

    /**
     * Regression test: the live-emitted card id and the stashed meta card id for a single
     * [AgentMonitorCoordinator.deliverToLoop] invocation must be identical so the
     * resume-synthesis dedup path catches the un-drained queue item.
     *
     * The bug: [monitorCardMeta] called [System.currentTimeMillis] internally while
     * [deliverToLoop] called it again inline — the two values could differ by ≥1 ms,
     * producing `mon-{id}-{ts1}` vs `mon-{id}-{ts2}` (different ids, dedup miss on resume).
     * The fix: compute [now] once and pass it to both callers.
     */
    @Test
    fun `deliverToLoop live-emitted card id equals stashed meta card id`() {
        // Use coalesceWindowMs = 0 so the first flush tick sees the event immediately.
        every { AgentSettings.getInstance(any()) } returns mockk {
            every { state } returns AgentSettings.State().apply {
                monitorCoalesceWindowMs = 0L
            }
        }

        val liveLoop: AgentLoop = mockk(relaxed = true)
        val enqueuedMessages = mutableListOf<QueuedMessage>()
        val emittedCards = mutableListOf<AsyncEventCardData>()

        val coordinator = buildCoordinator(
            activeLoopForSession = { liveLoop }, // loop is always live → deliverToLoop is called
            enqueueToQueue = { _, msg -> enqueuedMessages += msg },
            emitCard = { _, card -> emittedCards += card },
        )

        coordinator.ensureMonitorManager("sess-ts")

        // Emit a single monitor event via the bridge so the MonitorManager picks it up.
        MonitorBridge.emit(project, "sess-ts", MonitorEvent("shell-abc123", Severity.NOTABLE, "build finished"))

        // The flush loop ticks every 200 ms. With coalesceWindowMs = 0 the event is ready on
        // the very first tick. Wait up to 1 s (5× the interval) for it to arrive.
        val deadline = System.currentTimeMillis() + 1_000L
        while ((enqueuedMessages.isEmpty() || emittedCards.isEmpty()) && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }

        assertEquals(1, enqueuedMessages.size, "expected exactly one enqueued message")
        assertEquals(1, emittedCards.size, "expected exactly one live-emitted card")

        val metaCardJson = enqueuedMessages[0].meta["card"]
            ?: error("meta[\"card\"] not found on enqueued message")
        val metaCard = Json { ignoreUnknownKeys = true }.decodeFromString(AsyncEventCardData.serializer(), metaCardJson)

        assertEquals(
            emittedCards[0].id, metaCard.id,
            "live-emitted card id must equal stashed meta card id so resume dedup works",
        )
    }
}

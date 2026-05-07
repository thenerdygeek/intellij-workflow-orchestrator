package com.workflow.orchestrator.automation.service

import app.cash.turbine.test
import com.workflow.orchestrator.automation.api.DockerRegistryClient
import com.workflow.orchestrator.automation.model.QueueEntry
import com.workflow.orchestrator.automation.model.QueueEntryStatus
import com.workflow.orchestrator.core.events.EventBus
import com.workflow.orchestrator.core.events.WorkflowEvent
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.bamboo.BuildResultData
import com.workflow.orchestrator.core.model.bamboo.BuildTriggerData
import com.workflow.orchestrator.core.services.BambooService
import com.workflow.orchestrator.core.services.ToolResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class QueueServiceTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var bambooService: BambooService
    private lateinit var registryClient: DockerRegistryClient
    private lateinit var eventBus: EventBus
    private lateinit var tagHistory: TagHistoryService
    private lateinit var serviceScope: CoroutineScope
    private lateinit var service: QueueService

    /**
     * Wait for QueueService's IO-dispatched launches to settle.
     * After C7c, enqueue/cancel/restoreFromPersistence/polling launches use
     * Dispatchers.IO directly, so the test scheduler can't drive them.
     * Polls in real time (Dispatchers.IO) until the predicate holds, or fails on timeout.
     */
    private fun awaitState(timeoutMs: Long = 2000, predicate: () -> Boolean) {
        runBlocking(Dispatchers.IO) {
            withTimeout(timeoutMs) {
                while (!predicate()) {
                    delay(5)
                }
            }
        }
    }

    @BeforeEach
    fun setUp() {
        bambooService = mockk(relaxed = true)
        registryClient = mockk(relaxed = true)
        eventBus = EventBus()
        tagHistory = TagHistoryService(tempDir.resolve("test.db").toString())
        serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        service = QueueService(
            bambooService = bambooService,
            registryClient = registryClient,
            eventBus = eventBus,
            tagHistoryService = tagHistory,
            scope = serviceScope,
            autoTriggerEnabled = false,
            maxDepthPerSuite = 10,
            tagValidationOnTrigger = true
        )
    }

    @AfterEach
    fun tearDown() {
        tagHistory.close()
        serviceScope.cancel()
    }

    private fun makeEntry(
        id: String = "q-1",
        planKey: String = "PROJ-AUTO",
        status: QueueEntryStatus = QueueEntryStatus.WAITING_LOCAL
    ) = QueueEntry(
        id = id,
        suitePlanKey = planKey,
        dockerTagsPayload = """{"auth":"2.4.0"}""",
        variables = mapOf("suiteType" to "regression"),
        stages = listOf("QA Automation"),
        enqueuedAt = Instant.now(),
        status = status,
        bambooResultKey = null
    )

    @Test
    fun `enqueue adds entry to state flow`() = runTest {
        val entry = makeEntry()

        service.enqueue(entry)
        awaitState { service.stateFlow.value.isNotEmpty() }

        val entries = service.stateFlow.value
        assertEquals(1, entries.size)
        assertEquals("q-1", entries[0].id)
        assertEquals(QueueEntryStatus.WAITING_LOCAL, entries[0].status)
    }

    @Test
    fun `enqueue emits QueuePositionChanged event`() = runTest {
        eventBus.events.test {
            service.enqueue(makeEntry())

            val event = awaitItem()
            assertTrue(event is WorkflowEvent.QueuePositionChanged)
            val qpc = event as WorkflowEvent.QueuePositionChanged
            assertEquals(0, qpc.position)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `enqueue rejects when max depth exceeded`() = runTest {
        val smallService = QueueService(
            bambooService = bambooService,
            registryClient = registryClient,
            eventBus = eventBus,
            tagHistoryService = tagHistory,
            scope = serviceScope,
            autoTriggerEnabled = false,
            maxDepthPerSuite = 2,
            tagValidationOnTrigger = false
        )

        smallService.enqueue(makeEntry(id = "q-1"))
        awaitState { smallService.stateFlow.value.size == 1 }
        smallService.enqueue(makeEntry(id = "q-2"))
        awaitState { smallService.stateFlow.value.size == 2 }
        smallService.enqueue(makeEntry(id = "q-3"))
        // Third enqueue rejected; size stays at 2.  Poll briefly to confirm.
        runBlocking(Dispatchers.IO) { delay(100) }

        assertEquals(2, smallService.stateFlow.value.size)
    }

    @Test
    fun `cancel removes entry from state`() = runTest {
        service.enqueue(makeEntry())
        awaitState { service.stateFlow.value.isNotEmpty() }

        service.cancel("q-1")
        awaitState { service.stateFlow.value.isEmpty() }

        assertTrue(service.stateFlow.value.isEmpty())
    }

    @Test
    fun `getActiveEntries returns only non-terminal entries`() = runTest {
        service.enqueue(makeEntry(id = "q-1"))
        awaitState { service.stateFlow.value.size == 1 }
        service.enqueue(makeEntry(id = "q-2"))
        awaitState { service.stateFlow.value.size == 2 }

        service.cancel("q-1")
        awaitState { service.stateFlow.value.size == 1 }

        val active = service.getActiveEntries()
        assertEquals(1, active.size)
        assertEquals("q-2", active[0].id)
    }

    @Test
    fun `getQueuePositionForSuite returns correct position`() = runTest {
        service.enqueue(makeEntry(id = "q-1", planKey = "PROJ-AUTO"))
        awaitState { service.stateFlow.value.size == 1 }
        service.enqueue(makeEntry(id = "q-2", planKey = "PROJ-AUTO"))
        awaitState { service.stateFlow.value.size == 2 }

        assertEquals(0, service.getQueuePositionForSuite("PROJ-AUTO", "q-1"))
        assertEquals(1, service.getQueuePositionForSuite("PROJ-AUTO", "q-2"))
    }

    @Test
    fun `triggerNow bypasses queue and triggers immediately`() = runTest {
        val entry = makeEntry()
        coEvery { registryClient.tagExists(any(), any()) } returns ApiResult.Success(true)
        coEvery { bambooService.triggerBuild(any(), any()) } returns ToolResult.success(
            data = BuildTriggerData(
                buildKey = "PROJ-AUTO-850",
                buildNumber = 850,
                link = ""
            ),
            summary = "Build triggered"
        )

        val result = service.triggerNow(entry)

        assertFalse(result.isError)
        assertEquals("PROJ-AUTO-850", result.data)
    }

    @Test
    fun `payload snapshot is immutable after enqueue`() = runTest {
        val entry = makeEntry()
        service.enqueue(entry)
        awaitState { service.stateFlow.value.isNotEmpty() }

        val queued = service.stateFlow.value[0]
        assertEquals("""{"auth":"2.4.0"}""", queued.dockerTagsPayload)
    }

    // A-P0-5 regression: completed entries are removed from _stateFlow

    @Test
    fun `completed entries are removed from stateFlow after build finishes (A-P0-5)`() = runTest {
        // Arrange: two entries in QUEUED_ON_BAMBOO state
        val e1 = makeEntry(id = "q-1", status = QueueEntryStatus.QUEUED_ON_BAMBOO).copy(bambooResultKey = "PROJ-AUTO-100")
        val e2 = makeEntry(id = "q-2", status = QueueEntryStatus.QUEUED_ON_BAMBOO).copy(bambooResultKey = "PROJ-AUTO-101")

        // Manually seed state (bypass enqueue to avoid auto-trigger complexity)
        service.enqueue(makeEntry(id = "q-1"))
        awaitState { service.stateFlow.value.size == 1 }
        service.enqueue(makeEntry(id = "q-2"))
        awaitState { service.stateFlow.value.size == 2 }

        // Mock: both builds report Successful
        coEvery { bambooService.getBuild("PROJ-AUTO-100") } returns ToolResult.success(
            data = BuildResultData(
                planKey = "PROJ-AUTO", buildNumber = 100,
                state = "Successful", durationSeconds = 120,
                buildResultKey = "PROJ-AUTO-100"
            ),
            summary = "ok"
        )
        coEvery { bambooService.getBuild("PROJ-AUTO-101") } returns ToolResult.success(
            data = BuildResultData(
                planKey = "PROJ-AUTO", buildNumber = 101,
                state = "Successful", durationSeconds = 95,
                buildResultKey = "PROJ-AUTO-101"
            ),
            summary = "ok"
        )

        // Inject bambooResultKeys via cancel+re-enqueue workaround isn't clean;
        // Instead drive pollOnce directly with entries that already have bambooResultKey set.
        // Reset state with pre-seeded entries via reflection-free approach: use internal pollOnce.
        // We'll set up entries with bambooResultKey directly in the flow.
        // Approach: clear + directly set stateFlow via pollOnce with pre-configured entries.
        // Since stateFlow is internal, we verify the core behaviour via a service constructed
        // with pre-populated entries via the known path (enqueue then mutate via copy).

        // The cleanest path: assert that after pollOnce on QUEUED_ON_BAMBOO entries that resolve,
        // the entries are gone. We use the test constructor that lets us inject mocks cleanly.
        val scopeForTest = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val testService = QueueService(
            bambooService = bambooService,
            registryClient = registryClient,
            eventBus = eventBus,
            tagHistoryService = TagHistoryService(tempDir.resolve("test2.db").toString()),
            scope = scopeForTest,
            autoTriggerEnabled = false,
            maxDepthPerSuite = 10,
            tagValidationOnTrigger = false
        )

        // Enqueue two entries with bambooResultKeys already set
        val entry1 = QueueEntry(
            id = "x-1", suitePlanKey = "PROJ-AUTO",
            dockerTagsPayload = """{"auth":"1.0.0"}""",
            variables = emptyMap(), stages = emptyList(),
            enqueuedAt = Instant.now(),
            status = QueueEntryStatus.QUEUED_ON_BAMBOO,
            bambooResultKey = "PROJ-AUTO-100"
        )
        val entry2 = QueueEntry(
            id = "x-2", suitePlanKey = "PROJ-AUTO",
            dockerTagsPayload = """{"auth":"1.0.0"}""",
            variables = emptyMap(), stages = emptyList(),
            enqueuedAt = Instant.now(),
            status = QueueEntryStatus.QUEUED_ON_BAMBOO,
            bambooResultKey = "PROJ-AUTO-101"
        )

        testService.enqueue(entry1)
        awaitState(2000) { testService.stateFlow.value.size == 1 }
        testService.enqueue(entry2)
        awaitState(2000) { testService.stateFlow.value.size == 2 }

        // pollOnce will call getBuild for both entries → both Successful → both removed
        runBlocking(Dispatchers.IO) { testService.pollOnce() }

        // Assert: both completed entries removed from stateFlow (A-P0-5 fix)
        assertTrue(
            testService.stateFlow.value.isEmpty(),
            "Expected stateFlow to be empty after both builds completed, but was: ${testService.stateFlow.value}"
        )
        scopeForTest.cancel()
    }

    // A-P0-4 / A-P0-5 companion: Unknown state treated as terminal

    @Test
    fun `Unknown build state is treated as terminal and entry removed from stateFlow`() = runTest {
        coEvery { bambooService.getBuild("PROJ-AUTO-200") } returns ToolResult.success(
            data = BuildResultData(
                planKey = "PROJ-AUTO", buildNumber = 200,
                state = "Unknown", durationSeconds = 0,
                buildResultKey = "PROJ-AUTO-200"
            ),
            summary = "NotBuilt"
        )

        val scopeForTest = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val testService = QueueService(
            bambooService = bambooService,
            registryClient = registryClient,
            eventBus = eventBus,
            tagHistoryService = TagHistoryService(tempDir.resolve("test3.db").toString()),
            scope = scopeForTest,
            autoTriggerEnabled = false,
            maxDepthPerSuite = 10,
            tagValidationOnTrigger = false
        )

        val entry = QueueEntry(
            id = "u-1", suitePlanKey = "PROJ-AUTO",
            dockerTagsPayload = """{"auth":"1.0.0"}""",
            variables = emptyMap(), stages = emptyList(),
            enqueuedAt = Instant.now(),
            status = QueueEntryStatus.QUEUED_ON_BAMBOO,
            bambooResultKey = "PROJ-AUTO-200"
        )
        testService.enqueue(entry)
        awaitState(2000) { testService.stateFlow.value.size == 1 }

        runBlocking(Dispatchers.IO) { testService.pollOnce() }

        assertTrue(
            testService.stateFlow.value.isEmpty(),
            "Unknown/NotBuilt build should be removed from stateFlow (A-P0-5), but was: ${testService.stateFlow.value}"
        )
        scopeForTest.cancel()
    }
}

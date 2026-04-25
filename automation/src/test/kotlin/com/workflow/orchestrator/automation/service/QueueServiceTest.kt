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
}

package com.workflow.orchestrator.automation.service

import app.cash.turbine.test
import com.workflow.orchestrator.automation.api.DockerRegistryClient
import com.workflow.orchestrator.automation.model.QueueEntry
import com.workflow.orchestrator.automation.model.QueueEntryStatus
import com.workflow.orchestrator.bamboo.api.BambooApiClient
import com.workflow.orchestrator.bamboo.api.dto.BambooQueueResponse
import com.workflow.orchestrator.bamboo.api.dto.BambooResultDto
import com.workflow.orchestrator.bamboo.api.dto.BambooStageCollection
import com.workflow.orchestrator.core.events.EventBus
import com.workflow.orchestrator.core.events.WorkflowEvent
import com.workflow.orchestrator.core.model.ApiResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
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

    private lateinit var bambooClient: BambooApiClient
    private lateinit var registryClient: DockerRegistryClient
    private lateinit var eventBus: EventBus
    private lateinit var tagHistory: TagHistoryService
    private lateinit var testScope: TestScope
    private lateinit var service: QueueService

    @BeforeEach
    fun setUp() {
        bambooClient = mockk(relaxed = true)
        registryClient = mockk(relaxed = true)
        eventBus = EventBus()
        tagHistory = TagHistoryService(tempDir.resolve("test.db").toString())
        testScope = TestScope(StandardTestDispatcher())

        service = QueueService(
            bambooClient = bambooClient,
            registryClient = registryClient,
            eventBus = eventBus,
            tagHistoryService = tagHistory,
            scope = testScope,
            autoTriggerEnabled = false,
            maxDepthPerSuite = 10,
            tagValidationOnTrigger = true
        )
    }

    @AfterEach
    fun tearDown() {
        tagHistory.close()
        testScope.cancel()
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
    fun `enqueue adds entry to state flow`() = testScope.runTest {
        val entry = makeEntry()

        service.enqueue(entry)
        testScheduler.advanceUntilIdle()

        val entries = service.stateFlow.value
        assertEquals(1, entries.size)
        assertEquals("q-1", entries[0].id)
        assertEquals(QueueEntryStatus.WAITING_LOCAL, entries[0].status)
    }

    @Test
    fun `enqueue emits QueuePositionChanged event`() = testScope.runTest {
        eventBus.events.test {
            service.enqueue(makeEntry())
            testScheduler.advanceUntilIdle()

            val event = awaitItem()
            assertTrue(event is WorkflowEvent.QueuePositionChanged)
            val qpc = event as WorkflowEvent.QueuePositionChanged
            assertEquals(0, qpc.position)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `enqueue rejects when max depth exceeded`() = testScope.runTest {
        val smallService = QueueService(
            bambooClient = bambooClient,
            registryClient = registryClient,
            eventBus = eventBus,
            tagHistoryService = tagHistory,
            scope = testScope,
            autoTriggerEnabled = false,
            maxDepthPerSuite = 2,
            tagValidationOnTrigger = false
        )

        smallService.enqueue(makeEntry(id = "q-1"))
        testScheduler.advanceUntilIdle()
        smallService.enqueue(makeEntry(id = "q-2"))
        testScheduler.advanceUntilIdle()
        smallService.enqueue(makeEntry(id = "q-3"))
        testScheduler.advanceUntilIdle()

        assertEquals(2, smallService.stateFlow.value.size)
    }

    @Test
    fun `cancel removes entry from state`() = testScope.runTest {
        service.enqueue(makeEntry())
        testScheduler.advanceUntilIdle()

        service.cancel("q-1")
        testScheduler.advanceUntilIdle()

        assertTrue(service.stateFlow.value.isEmpty())
    }

    @Test
    fun `getActiveEntries returns only non-terminal entries`() = testScope.runTest {
        service.enqueue(makeEntry(id = "q-1"))
        testScheduler.advanceUntilIdle()
        service.enqueue(makeEntry(id = "q-2"))
        testScheduler.advanceUntilIdle()

        service.cancel("q-1")
        testScheduler.advanceUntilIdle()

        val active = service.getActiveEntries()
        assertEquals(1, active.size)
        assertEquals("q-2", active[0].id)
    }

    @Test
    fun `getQueuePositionForSuite returns correct position`() = testScope.runTest {
        service.enqueue(makeEntry(id = "q-1", planKey = "PROJ-AUTO"))
        testScheduler.advanceUntilIdle()
        service.enqueue(makeEntry(id = "q-2", planKey = "PROJ-AUTO"))
        testScheduler.advanceUntilIdle()

        assertEquals(0, service.getQueuePositionForSuite("PROJ-AUTO", "q-1"))
        assertEquals(1, service.getQueuePositionForSuite("PROJ-AUTO", "q-2"))
    }

    @Test
    fun `triggerNow bypasses queue and triggers immediately`() = testScope.runTest {
        val entry = makeEntry()
        coEvery { registryClient.tagExists(any(), any()) } returns ApiResult.Success(true)
        coEvery { bambooClient.triggerBuild(any(), any(), any()) } returns ApiResult.Success(
            BambooQueueResponse(
                buildResultKey = "PROJ-AUTO-850",
                buildNumber = 850,
                planKey = "PROJ-AUTO"
            )
        )

        val result = service.triggerNow(entry)

        assertTrue(result is ApiResult.Success)
        assertEquals("PROJ-AUTO-850", (result as ApiResult.Success).data)
    }

    @Test
    fun `payload snapshot is immutable after enqueue`() = testScope.runTest {
        val entry = makeEntry()
        service.enqueue(entry)
        testScheduler.advanceUntilIdle()

        val queued = service.stateFlow.value[0]
        assertEquals("""{"auth":"2.4.0"}""", queued.dockerTagsPayload)
    }
}

package com.workflow.orchestrator.bamboo.service

import app.cash.turbine.test
import com.workflow.orchestrator.bamboo.api.BambooApiClient
import com.workflow.orchestrator.bamboo.api.dto.BambooResultDto
import com.workflow.orchestrator.bamboo.api.dto.BambooStageCollection
import com.workflow.orchestrator.bamboo.api.dto.BambooJobResultCollection
import com.workflow.orchestrator.bamboo.api.dto.BambooJobResultDto
import com.workflow.orchestrator.bamboo.api.dto.BambooStageDto
import com.workflow.orchestrator.bamboo.model.BuildStatus
import com.workflow.orchestrator.core.events.EventBus
import com.workflow.orchestrator.core.events.WorkflowEvent
import com.workflow.orchestrator.core.model.ApiResult
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BuildMonitorServiceTest {

    private val apiClient = mockk<BambooApiClient>()
    private val eventBus = EventBus()

    private fun makeResult(state: String, lifeCycle: String, buildNumber: Int = 42): BambooResultDto {
        return BambooResultDto(
            key = "PROJ-BUILD-$buildNumber",
            buildNumber = buildNumber,
            state = state,
            lifeCycleState = lifeCycle,
            buildDurationInSeconds = 120,
            stages = BambooStageCollection(
                size = 2,
                stage = listOf(
                    BambooStageDto(
                        name = "Compile",
                        state = "Successful",
                        lifeCycleState = "Finished",
                        results = BambooJobResultCollection(
                            size = 1,
                            result = listOf(
                                BambooJobResultDto(
                                    buildResultKey = "PROJ-BUILD-JOB1-$buildNumber",
                                    state = "Successful",
                                    lifeCycleState = "Finished"
                                )
                            )
                        )
                    ),
                    BambooStageDto(
                        name = "Test",
                        state = state,
                        lifeCycleState = lifeCycle,
                        results = BambooJobResultCollection(
                            size = 1,
                            result = listOf(
                                BambooJobResultDto(
                                    buildResultKey = "PROJ-BUILD-JOB2-$buildNumber",
                                    state = state,
                                    lifeCycleState = lifeCycle
                                )
                            )
                        )
                    )
                )
            )
        )
    }

    @Test
    fun `pollOnce updates stateFlow with build result`() = runTest {
        val result = makeResult("Successful", "Finished")
        coEvery { apiClient.getLatestResult("PROJ-BUILD", "main") } returns ApiResult.Success(result)
        coEvery { apiClient.getBuildLog("PROJ-BUILD-JOB1-42") } returns ApiResult.Success("log content")

        val service = BuildMonitorService(apiClient, eventBus, this)

        service.stateFlow.test {
            // Initial state
            assertNull(awaitItem())

            service.pollOnce("PROJ-BUILD", "main")

            val state = awaitItem()
            assertNotNull(state)
            assertEquals("PROJ-BUILD", state!!.planKey)
            assertEquals("main", state.branch)
            assertEquals(BuildStatus.SUCCESS, state.overallStatus)
            assertEquals(2, state.stages.size)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `pollOnce emits BuildFinished event on status change`() = runTest {
        // First poll returns in-progress build (initializes state, no event)
        val inProgressResult = makeResult("InProgress", "InProgress")
        coEvery { apiClient.getLatestResult("PROJ-BUILD", "main") } returns ApiResult.Success(inProgressResult)

        val service = BuildMonitorService(apiClient, eventBus, this)
        service.pollOnce("PROJ-BUILD", "main")

        // Second poll returns completed build → status changed → event emitted
        val successResult = makeResult("Successful", "Finished")
        coEvery { apiClient.getLatestResult("PROJ-BUILD", "main") } returns ApiResult.Success(successResult)
        coEvery { apiClient.getBuildLog("PROJ-BUILD-JOB1-42") } returns ApiResult.Success("log content")

        eventBus.events.test {
            service.pollOnce("PROJ-BUILD", "main")

            val event = awaitItem()
            assertTrue(event is WorkflowEvent.BuildFinished)
            val buildEvent = event as WorkflowEvent.BuildFinished
            assertEquals("PROJ-BUILD", buildEvent.planKey)
            assertEquals(WorkflowEvent.BuildEventStatus.SUCCESS, buildEvent.status)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `pollOnce does not emit event when status unchanged`() = runTest {
        val result = makeResult("Successful", "Finished")
        coEvery { apiClient.getLatestResult("PROJ-BUILD", "main") } returns ApiResult.Success(result)
        coEvery { apiClient.getBuildLog("PROJ-BUILD-JOB1-42") } returns ApiResult.Success("log content")

        val service = BuildMonitorService(apiClient, eventBus, this)

        // First poll — should emit
        service.pollOnce("PROJ-BUILD", "main")

        eventBus.events.test {
            // Second poll with same result — should NOT emit
            service.pollOnce("PROJ-BUILD", "main")
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `pollOnce emits FAILED event on failed build`() = runTest {
        // First poll returns in-progress build (initializes state, no event)
        val inProgressResult = makeResult("InProgress", "InProgress")
        coEvery { apiClient.getLatestResult("PROJ-BUILD", "main") } returns ApiResult.Success(inProgressResult)

        val service = BuildMonitorService(apiClient, eventBus, this)
        service.pollOnce("PROJ-BUILD", "main")

        // Second poll returns failed build → status changed → event emitted
        val failedResult = makeResult("Failed", "Finished")
        coEvery { apiClient.getLatestResult("PROJ-BUILD", "main") } returns ApiResult.Success(failedResult)
        coEvery { apiClient.getBuildLog("PROJ-BUILD-JOB1-42") } returns ApiResult.Success("build failed log")

        eventBus.events.test {
            service.pollOnce("PROJ-BUILD", "main")

            val event = awaitItem() as WorkflowEvent.BuildFinished
            assertEquals(WorkflowEvent.BuildEventStatus.FAILED, event.status)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `pollOnce emits BuildLogReady on first terminal poll`() = runTest {
        val result = makeResult("Successful", "Finished")
        coEvery { apiClient.getLatestResult("PROJ-BUILD", "main") } returns ApiResult.Success(result)
        coEvery { apiClient.getBuildLog("PROJ-BUILD-JOB1-42") } returns ApiResult.Success("Unique Docker Tag : my-tag-123")

        val service = BuildMonitorService(apiClient, eventBus, this)

        eventBus.events.test {
            service.pollOnce("PROJ-BUILD", "main")

            // First poll of terminal build emits BuildLogReady (but NOT BuildFinished)
            val event = awaitItem()
            assertTrue(event is WorkflowEvent.BuildLogReady)
            val logEvent = event as WorkflowEvent.BuildLogReady
            assertEquals("PROJ-BUILD", logEvent.planKey)
            assertEquals(42, logEvent.buildNumber)
            assertEquals("PROJ-BUILD-JOB1-42", logEvent.resultKey)
            assertEquals(WorkflowEvent.BuildEventStatus.SUCCESS, logEvent.status)
            assertEquals("Unique Docker Tag : my-tag-123", logEvent.logText)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `pollOnce emits BuildLogReady with empty text on log fetch failure`() = runTest {
        val result = makeResult("Successful", "Finished")
        coEvery { apiClient.getLatestResult("PROJ-BUILD", "main") } returns ApiResult.Success(result)
        coEvery { apiClient.getBuildLog("PROJ-BUILD-JOB1-42") } returns
            ApiResult.Error(com.workflow.orchestrator.core.model.ErrorType.NETWORK_ERROR, "timeout")

        val service = BuildMonitorService(apiClient, eventBus, this)

        eventBus.events.test {
            service.pollOnce("PROJ-BUILD", "main")

            val event = awaitItem()
            assertTrue(event is WorkflowEvent.BuildLogReady)
            assertEquals("", (event as WorkflowEvent.BuildLogReady).logText)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `pollOnce does not re-fetch log for same build number`() = runTest {
        val result = makeResult("Successful", "Finished")
        coEvery { apiClient.getLatestResult("PROJ-BUILD", "main") } returns ApiResult.Success(result)
        coEvery { apiClient.getBuildLog("PROJ-BUILD-JOB1-42") } returns ApiResult.Success("log")

        val service = BuildMonitorService(apiClient, eventBus, this)

        // First poll — fetches log
        service.pollOnce("PROJ-BUILD", "main")

        eventBus.events.test {
            // Second poll with same build — should NOT emit BuildLogReady again
            service.pollOnce("PROJ-BUILD", "main")
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `pollOnce emits BuildLogReady with FAILED status for failed build`() = runTest {
        val inProgress = makeResult("InProgress", "InProgress")
        coEvery { apiClient.getLatestResult("PROJ-BUILD", "main") } returns ApiResult.Success(inProgress)

        val service = BuildMonitorService(apiClient, eventBus, this)
        service.pollOnce("PROJ-BUILD", "main")

        val failed = makeResult("Failed", "Finished")
        coEvery { apiClient.getLatestResult("PROJ-BUILD", "main") } returns ApiResult.Success(failed)
        coEvery { apiClient.getBuildLog("PROJ-BUILD-JOB1-42") } returns ApiResult.Success("build failed")

        eventBus.events.test {
            service.pollOnce("PROJ-BUILD", "main")

            // BuildFinished first
            val finishedEvent = awaitItem()
            assertTrue(finishedEvent is WorkflowEvent.BuildFinished)

            // Then BuildLogReady
            val logEvent = awaitItem()
            assertTrue(logEvent is WorkflowEvent.BuildLogReady)
            assertEquals(WorkflowEvent.BuildEventStatus.FAILED, (logEvent as WorkflowEvent.BuildLogReady).status)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `pollOnce falls back to plan-level key when stages have no job resultKey`() = runTest {
        val result = BambooResultDto(
            key = "PROJ-BUILD-42",
            buildNumber = 42,
            state = "Successful",
            lifeCycleState = "Finished",
            buildDurationInSeconds = 120,
            stages = BambooStageCollection(size = 0, stage = emptyList())
        )
        coEvery { apiClient.getLatestResult("PROJ-BUILD", "main") } returns ApiResult.Success(result)
        coEvery { apiClient.getBuildLog("PROJ-BUILD-42") } returns ApiResult.Success("fallback log")

        val service = BuildMonitorService(apiClient, eventBus, this)

        eventBus.events.test {
            service.pollOnce("PROJ-BUILD", "main")

            val event = awaitItem()
            assertTrue(event is WorkflowEvent.BuildLogReady)
            val logEvent = event as WorkflowEvent.BuildLogReady
            assertEquals("PROJ-BUILD-42", logEvent.resultKey)
            assertEquals("fallback log", logEvent.logText)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `pollOnce handles API error gracefully`() = runTest {
        coEvery { apiClient.getLatestResult("PROJ-BUILD", "main") } returns
            ApiResult.Error(com.workflow.orchestrator.core.model.ErrorType.NETWORK_ERROR, "Connection refused")

        val service = BuildMonitorService(apiClient, eventBus, this)

        service.stateFlow.test {
            assertNull(awaitItem()) // initial null
            service.pollOnce("PROJ-BUILD", "main")
            // State should remain null on error
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }
}

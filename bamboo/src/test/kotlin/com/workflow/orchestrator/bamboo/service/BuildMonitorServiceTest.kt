package com.workflow.orchestrator.bamboo.service

import app.cash.turbine.test
import com.workflow.orchestrator.bamboo.api.BambooApiClient
import com.workflow.orchestrator.bamboo.api.dto.BambooResultDto
import com.workflow.orchestrator.bamboo.api.dto.BambooStageCollection
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
                    BambooStageDto(name = "Compile", state = "Successful", lifeCycleState = "Finished"),
                    BambooStageDto(name = "Test", state = state, lifeCycleState = lifeCycle)
                )
            )
        )
    }

    @Test
    fun `pollOnce updates stateFlow with build result`() = runTest {
        val result = makeResult("Successful", "Finished")
        coEvery { apiClient.getLatestResult("PROJ-BUILD", "main") } returns ApiResult.Success(result)

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
        val successResult = makeResult("Successful", "Finished")
        coEvery { apiClient.getLatestResult("PROJ-BUILD", "main") } returns ApiResult.Success(successResult)

        val service = BuildMonitorService(apiClient, eventBus, this)

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
        val failedResult = makeResult("Failed", "Finished")
        coEvery { apiClient.getLatestResult("PROJ-BUILD", "main") } returns ApiResult.Success(failedResult)

        val service = BuildMonitorService(apiClient, eventBus, this)

        eventBus.events.test {
            service.pollOnce("PROJ-BUILD", "main")

            val event = awaitItem() as WorkflowEvent.BuildFinished
            assertEquals(WorkflowEvent.BuildEventStatus.FAILED, event.status)

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

package com.workflow.orchestrator.bamboo.service

import com.workflow.orchestrator.bamboo.api.BambooApiClient
import com.workflow.orchestrator.bamboo.api.dto.BambooJobResultCollection
import com.workflow.orchestrator.bamboo.api.dto.BambooJobResultDto
import com.workflow.orchestrator.bamboo.api.dto.BambooPlanConfigResponse
import com.workflow.orchestrator.bamboo.api.dto.BambooResultDto
import com.workflow.orchestrator.bamboo.api.dto.BambooStageCollection
import com.workflow.orchestrator.bamboo.api.dto.BambooStageDto
import com.workflow.orchestrator.core.events.EventBus
import com.workflow.orchestrator.core.model.ApiResult
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * P0-5 (2026-06-10 perf audit): the focusBuild auto-seed at project startup (T-AutoSeed)
 * started a SmartPoller that ran at FULL rate for the project lifetime even when the Build
 * tab was never opened (`SmartPoller.visible` defaults true).
 *
 * Observer-gated contract pinned here:
 * - Polling starts with NO observer (tab hidden) until [BuildMonitorService.setVisible] is
 *   called with true — so background polling runs at the 4× interval, not full rate.
 * - When the focused build is in a TERMINAL state AND no observer is attached, polling STOPS
 *   entirely (the BuildFinished/BuildLogReady consumers already got their events).
 * - A new observer ([setVisible] true) restarts auto-stopped polling WITHOUT resetting the
 *   dedupe state (no duplicate BuildFinished, no log refetch).
 * - A non-terminal (running) build keeps ambient polling alive even with no observer, so
 *   Automation/Handover event consumers still see the eventual terminal transition.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BuildMonitorObserverGatingTest {

    private val apiClient = mockk<BambooApiClient>()
    private val eventBus = EventBus()

    private val resultPolls = AtomicInteger(0)
    private val logFetches = AtomicInteger(0)

    @BeforeEach
    fun stubCollaterals() {
        coEvery { apiClient.getPlanStructure(any()) } returns ApiResult.Success(BambooPlanConfigResponse())
        coEvery { apiClient.getRunningAndQueuedBuilds(any()) } returns ApiResult.Success(emptyList())
        coEvery { apiClient.getBuildLog(any()) } answers {
            logFetches.incrementAndGet()
            ApiResult.Success("log")
        }
    }

    private fun makeResult(state: String, lifeCycle: String, buildNumber: Int = 42): BambooResultDto =
        BambooResultDto(
            key = "PROJ-BUILD-$buildNumber",
            buildNumber = buildNumber,
            state = state,
            lifeCycleState = lifeCycle,
            buildDurationInSeconds = 120,
            stages = BambooStageCollection(
                stage = listOf(
                    BambooStageDto(
                        name = "Test",
                        state = state,
                        lifeCycleState = lifeCycle,
                        results = BambooJobResultCollection(
                            result = listOf(
                                BambooJobResultDto(
                                    buildResultKey = "PROJ-BUILD-JOB1-$buildNumber",
                                    state = state,
                                    lifeCycleState = lifeCycle,
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

    private fun stubLatestResult(dto: BambooResultDto) {
        coEvery { apiClient.getLatestResult("PROJ-BUILD") } answers {
            resultPolls.incrementAndGet()
            ApiResult.Success(dto)
        }
    }

    @Test
    fun `terminal build with no observer auto-stops polling after the first poll`() = runTest {
        stubLatestResult(makeResult("Successful", "Finished"))
        val service = BuildMonitorService(apiClient, eventBus, this)

        service.startPolling("PROJ-BUILD", "main", 1_000)
        runCurrent() // first poll fires at t=0

        assertEquals(1, resultPolls.get(), "first poll must still run (hydrates event consumers)")

        advanceTimeBy(600_000)
        runCurrent()
        assertEquals(
            1,
            resultPolls.get(),
            "terminal build + hidden tab must STOP polling entirely, not keep polling for the project lifetime",
        )
        service.stopPolling()
    }

    @Test
    fun `visible observer keeps polling a terminal build`() = runTest {
        stubLatestResult(makeResult("Successful", "Finished"))
        val service = BuildMonitorService(apiClient, eventBus, this)

        service.setVisible(true)
        service.startPolling("PROJ-BUILD", "main", 1_000)
        runCurrent()

        advanceTimeBy(120_000)
        runCurrent()
        assertTrue(
            resultPolls.get() >= 2,
            "visible Build tab must keep polling (newer-build detection), got ${resultPolls.get()}",
        )
        service.stopPolling()
    }

    @Test
    fun `setVisible true restarts auto-stopped polling without resetting dedupe state`() = runTest {
        stubLatestResult(makeResult("Successful", "Finished"))
        val service = BuildMonitorService(apiClient, eventBus, this)

        service.startPolling("PROJ-BUILD", "main", 1_000)
        runCurrent() // poll 1 → terminal + hidden → auto-stop
        assertEquals(1, resultPolls.get())
        val logFetchesAfterFirstPoll = logFetches.get()
        assertTrue(logFetchesAfterFirstPoll >= 1, "terminal first poll fetches the build log")

        service.setVisible(true) // observer attached → restart polling
        runCurrent()

        assertEquals(2, resultPolls.get(), "observer attach must immediately resume polling")
        assertEquals(
            logFetchesAfterFirstPoll,
            logFetches.get(),
            "restart must preserve dedupe state — same finished build must NOT refetch its logs",
        )
        service.stopPolling()
    }

    @Test
    fun `non-terminal build keeps ambient polling with no observer`() = runTest {
        stubLatestResult(makeResult("Unknown", "InProgress"))
        val service = BuildMonitorService(apiClient, eventBus, this)

        service.startPolling("PROJ-BUILD", "main", 1_000)
        runCurrent()

        advanceTimeBy(120_000)
        runCurrent()
        assertTrue(
            resultPolls.get() >= 2,
            "running build must keep ambient (background-rate) polling so Automation/Handover " +
                "consumers see the terminal transition, got ${resultPolls.get()}",
        )
        service.stopPolling()
    }
}

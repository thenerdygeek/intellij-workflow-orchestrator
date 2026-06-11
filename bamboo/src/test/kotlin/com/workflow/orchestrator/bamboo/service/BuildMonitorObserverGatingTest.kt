package com.workflow.orchestrator.bamboo.service

import com.workflow.orchestrator.bamboo.api.BambooApiClient
import com.workflow.orchestrator.bamboo.api.dto.BambooJobResultCollection
import com.workflow.orchestrator.bamboo.api.dto.BambooJobResultDto
import com.workflow.orchestrator.bamboo.api.dto.BambooPlanConfigResponse
import com.workflow.orchestrator.bamboo.api.dto.BambooResultDto
import com.workflow.orchestrator.bamboo.api.dto.BambooStageCollection
import com.workflow.orchestrator.bamboo.api.dto.BambooStageDto
import com.workflow.orchestrator.core.events.EventBus
import com.workflow.orchestrator.core.events.WorkflowEvent
import com.workflow.orchestrator.core.model.ApiResult
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
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
 * Observer-gated contract pinned here (heartbeat revision, W5-C1 review):
 * - Polling starts with NO observer (tab hidden) until [BuildMonitorService.setVisible] is
 *   called with true — so background polling runs at the 4× interval, not full rate.
 * - When the focused build is in a TERMINAL state AND no observer is attached, polling drops
 *   to a long HEARTBEAT cadence (~5 min) instead of stopping: a remotely-triggered NEW build
 *   on the same plan must still reach the ambient consumers (HandoverStateService.buildStatus,
 *   TagBuilderService docker-tag cache) — a full stop would leave them stale forever.
 * - When a heartbeat poll spots new activity (new build number/status, or a newer running
 *   build), normal background polling resumes; the preserved dedupe state guarantees the
 *   one-shot BuildFinished for the new build.
 * - A new observer ([setVisible] true) during heartbeat resumes normal polling WITHOUT
 *   resetting the dedupe state (no duplicate BuildFinished, no log refetch).
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
    fun `terminal build with no observer drops to heartbeat cadence`() = runTest {
        stubLatestResult(makeResult("Successful", "Finished"))
        val service = BuildMonitorService(apiClient, eventBus, this)

        service.startPolling("PROJ-BUILD", "main", 1_000)
        runCurrent() // poll 1 at t=0 → terminal+hidden → drop to heartbeat (immediate heartbeat poll)

        val pollsAtDrop = resultPolls.get()
        assertTrue(pollsAtDrop in 1..2, "initial poll (+ heartbeat relaunch poll) expected, got $pollsAtDrop")

        // Heartbeat cadence is ~300s (±10% jitter): exactly 3 heartbeat ticks fit in 1000s.
        advanceTimeBy(1_000_000)
        runCurrent()
        val polls = resultPolls.get()
        assertTrue(
            polls > pollsAtDrop,
            "heartbeat must keep checking for a remotely-triggered NEW build (full stop would " +
                "leave Handover/Automation consumers stale forever), got $polls",
        )
        assertTrue(
            polls <= pollsAtDrop + 4,
            "terminal+unobserved cadence must be the long heartbeat (~5 min), not background " +
                "base-rate polling, got $polls",
        )
        service.stopPolling()
    }

    @Test
    fun `newer finished build during heartbeat fires BuildFinished exactly once`() = runTest {
        var currentDto = makeResult("Successful", "Finished", buildNumber = 42)
        coEvery { apiClient.getLatestResult("PROJ-BUILD") } answers {
            resultPolls.incrementAndGet()
            ApiResult.Success(currentDto)
        }
        val buildFinished = mutableListOf<WorkflowEvent.BuildFinished>()
        val collector = launch {
            eventBus.events.collect { if (it is WorkflowEvent.BuildFinished) buildFinished += it }
        }
        val service = BuildMonitorService(apiClient, eventBus, this)

        service.startPolling("PROJ-BUILD", "main", 1_000)
        runCurrent() // poll #42 (first poll → no event) → heartbeat
        assertTrue(buildFinished.isEmpty(), "first poll must not fire BuildFinished")

        // A remote build #43 runs AND finishes between heartbeats.
        currentDto = makeResult("Successful", "Finished", buildNumber = 43)
        advanceTimeBy(340_000) // covers one heartbeat tick (~300-330s)
        runCurrent()

        assertEquals(1, buildFinished.size, "heartbeat must surface the new build's BuildFinished exactly once")
        assertEquals(43, buildFinished[0].buildNumber)

        advanceTimeBy(700_000) // further heartbeats see no change → no duplicate events
        runCurrent()
        assertEquals(1, buildFinished.size, "no duplicate BuildFinished after the one-shot emit")

        collector.cancel()
        service.stopPolling()
    }

    @Test
    fun `newer running build during heartbeat resumes normal background polling`() = runTest {
        var currentDto = makeResult("Successful", "Finished", buildNumber = 42)
        coEvery { apiClient.getLatestResult("PROJ-BUILD") } answers {
            resultPolls.incrementAndGet()
            ApiResult.Success(currentDto)
        }
        val service = BuildMonitorService(apiClient, eventBus, this)

        service.startPolling("PROJ-BUILD", "main", 1_000)
        runCurrent() // poll #42 → terminal+hidden → heartbeat

        // A remote build #43 starts running; the next heartbeat tick must resume normal polling.
        currentDto = makeResult("Unknown", "InProgress", buildNumber = 43)
        advanceTimeBy(340_000) // one heartbeat tick
        runCurrent()
        val pollsAtResume = resultPolls.get()

        advanceTimeBy(60_000) // heartbeat cadence would fit ZERO polls here; normal cadence fits ≥1
        runCurrent()
        assertTrue(
            resultPolls.get() > pollsAtResume,
            "a newer RUNNING build must flip the heartbeat back to normal background polling " +
                "(got ${resultPolls.get()} vs $pollsAtResume at resume)",
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
    fun `setVisible true resumes normal polling from heartbeat without resetting dedupe state`() = runTest {
        stubLatestResult(makeResult("Successful", "Finished"))
        val service = BuildMonitorService(apiClient, eventBus, this)

        service.startPolling("PROJ-BUILD", "main", 1_000)
        runCurrent() // poll 1 → terminal + hidden → heartbeat (immediate heartbeat poll)
        val pollsAtDrop = resultPolls.get()
        val logFetchesAfterFirstPoll = logFetches.get()
        assertTrue(logFetchesAfterFirstPoll >= 1, "terminal first poll fetches the build log")

        service.setVisible(true) // observer attached → resume normal cadence (immediate poll)
        runCurrent()

        assertTrue(
            resultPolls.get() > pollsAtDrop,
            "observer attach must immediately resume normal polling",
        )
        assertEquals(
            logFetchesAfterFirstPoll,
            logFetches.get(),
            "resume must preserve dedupe state — same finished build must NOT refetch its logs",
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

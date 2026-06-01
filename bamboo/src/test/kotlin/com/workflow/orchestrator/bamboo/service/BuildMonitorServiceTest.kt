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
import com.workflow.orchestrator.core.services.BuildLogCache
import com.workflow.orchestrator.bamboo.api.dto.BambooPlanConfigResponse
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BuildMonitorServiceTest {

    private val apiClient = mockk<BambooApiClient>()
    private val eventBus = EventBus()
    private val buildLogCache = BuildLogCache()

    @BeforeEach
    fun stubPlanStructure() {
        // pollOnce now fetches the plan definition to order jobs. These tests don't assert on
        // ordering, so return an empty config → the mapper keeps the result's job order.
        coEvery { apiClient.getPlanStructure(any()) } returns ApiResult.Success(BambooPlanConfigResponse())
        // checkForNewerBuild() is called on every terminal-state poll. Without this stub the
        // strict mock throws MockKException which is then swallowed by the catch block in
        // checkForNewerBuild(), causing tests to silently skip the newer-build detection path.
        // Default to empty → no newer build (safe, does not affect other assertions).
        coEvery { apiClient.getRunningAndQueuedBuilds(any()) } returns ApiResult.Success(emptyList())
    }

    private fun makeResult(state: String, lifeCycle: String, buildNumber: Int = 42): BambooResultDto {
        return BambooResultDto(
            key = "PROJ-BUILD-$buildNumber",
            buildNumber = buildNumber,
            state = state,
            lifeCycleState = lifeCycle,
            buildDurationInSeconds = 120,
            stages = BambooStageCollection(
                stage = listOf(
                    BambooStageDto(
                        name = "Compile",
                        state = "Successful",
                        lifeCycleState = "Finished",
                        results = BambooJobResultCollection(
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

    /** Sets up mocks for both job logs (makeResult creates JOB1 + JOB2). */
    private fun mockJobLogs(
        buildNumber: Int = 42,
        job1Log: ApiResult<String> = ApiResult.Success(""),
        job2Log: ApiResult<String> = ApiResult.Success("")
    ) {
        coEvery { apiClient.getBuildLog("PROJ-BUILD-JOB1-$buildNumber") } returns job1Log
        coEvery { apiClient.getBuildLog("PROJ-BUILD-JOB2-$buildNumber") } returns job2Log
    }

    @Test
    fun `pollOnce updates stateFlow with build result`() = runTest {
        val result = makeResult("Successful", "Finished")
        coEvery { apiClient.getLatestResult("PROJ-BUILD") } returns ApiResult.Success(result)
        mockJobLogs(job1Log = ApiResult.Success("log content"))

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
        coEvery { apiClient.getLatestResult("PROJ-BUILD") } returns ApiResult.Success(inProgressResult)

        val service = BuildMonitorService(apiClient, eventBus, this)
        service.pollOnce("PROJ-BUILD", "main")

        // Second poll returns completed build → status changed → event emitted
        val successResult = makeResult("Successful", "Finished")
        coEvery { apiClient.getLatestResult("PROJ-BUILD") } returns ApiResult.Success(successResult)
        mockJobLogs(job1Log = ApiResult.Success("log content"))

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
        coEvery { apiClient.getLatestResult("PROJ-BUILD") } returns ApiResult.Success(result)
        mockJobLogs(job1Log = ApiResult.Success("log content"))

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
        coEvery { apiClient.getLatestResult("PROJ-BUILD") } returns ApiResult.Success(inProgressResult)

        val service = BuildMonitorService(apiClient, eventBus, this)
        service.pollOnce("PROJ-BUILD", "main")

        // Second poll returns failed build → status changed → event emitted
        val failedResult = makeResult("Failed", "Finished")
        coEvery { apiClient.getLatestResult("PROJ-BUILD") } returns ApiResult.Success(failedResult)
        mockJobLogs(job1Log = ApiResult.Success("build failed log"))

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
        coEvery { apiClient.getLatestResult("PROJ-BUILD") } returns ApiResult.Success(result)
        mockJobLogs(
            job1Log = ApiResult.Success("Compiling..."),
            job2Log = ApiResult.Success("Unique Docker Tag : my-tag-123")
        )

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
            // Logs from both jobs are concatenated — tag is in JOB2's log
            assertTrue(logEvent.logText.contains("Compiling..."))
            assertTrue(logEvent.logText.contains("Unique Docker Tag : my-tag-123"))

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `pollOnce emits BuildLogReady with empty text on log fetch failure`() = runTest {
        val result = makeResult("Successful", "Finished")
        coEvery { apiClient.getLatestResult("PROJ-BUILD") } returns ApiResult.Success(result)
        mockJobLogs(
            job1Log = ApiResult.Error(com.workflow.orchestrator.core.model.ErrorType.NETWORK_ERROR, "timeout"),
            job2Log = ApiResult.Error(com.workflow.orchestrator.core.model.ErrorType.NETWORK_ERROR, "timeout")
        )

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
    fun `pollOnce does not re-fetch log for same build number when first fetch was complete`() = runTest {
        val result = makeResult("Successful", "Finished")
        coEvery { apiClient.getLatestResult("PROJ-BUILD") } returns ApiResult.Success(result)
        // Both job logs return Success → fetch is "complete" → no retry
        mockJobLogs(
            job1Log = ApiResult.Success("Compiling..."),
            job2Log = ApiResult.Success("Done.")
        )

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
    fun `pollOnce re-fetches log on next poll when previous attempt had a job log error`() = runTest {
        // Repro for the "Unique Docker Tag never appears" bug: when Bamboo reports
        // a build as Successful slightly before all job logs are flushed, the per-job
        // getBuildLog call for the publish job returns an error. The current code
        // marked the build as fetched anyway, stranding the panel with a tag-less
        // log forever. Fix: only mark as fetched when every job log returned Success.
        val result = makeResult("Successful", "Finished")
        coEvery { apiClient.getLatestResult("PROJ-BUILD") } returns ApiResult.Success(result)

        val service = BuildMonitorService(apiClient, eventBus, this, buildLogCache = buildLogCache)

        // First poll: JOB2 (publish) log fetch fails → log is incomplete
        mockJobLogs(
            job1Log = ApiResult.Success("Compiling..."),
            job2Log = ApiResult.Error(com.workflow.orchestrator.core.model.ErrorType.NETWORK_ERROR, "log not yet flushed")
        )
        service.pollOnce("PROJ-BUILD", "main")
        val firstCached = buildLogCache.getLatest("PROJ-BUILD")
        assertNotNull(firstCached)
        assertFalse(firstCached!!.logText.contains("Unique Docker Tag"))

        // Second poll: JOB2 log now succeeds with the tag — must re-emit and overwrite cache
        mockJobLogs(
            job1Log = ApiResult.Success("Compiling..."),
            job2Log = ApiResult.Success("Publishing image\nUnique Docker Tag : feature-late-flush\nDone.")
        )

        eventBus.events.test {
            service.pollOnce("PROJ-BUILD", "main")
            val event = awaitItem() as WorkflowEvent.BuildLogReady
            assertTrue(event.logText.contains("Unique Docker Tag : feature-late-flush"))
            cancelAndIgnoreRemainingEvents()
        }

        val secondCached = buildLogCache.getLatest("PROJ-BUILD")
        assertNotNull(secondCached)
        assertTrue(secondCached!!.logText.contains("Unique Docker Tag : feature-late-flush"))
    }

    @Test
    fun `pollOnce emits BuildLogReady with FAILED status for failed build`() = runTest {
        val inProgress = makeResult("InProgress", "InProgress")
        coEvery { apiClient.getLatestResult("PROJ-BUILD") } returns ApiResult.Success(inProgress)

        val service = BuildMonitorService(apiClient, eventBus, this)
        service.pollOnce("PROJ-BUILD", "main")

        val failed = makeResult("Failed", "Finished")
        coEvery { apiClient.getLatestResult("PROJ-BUILD") } returns ApiResult.Success(failed)
        mockJobLogs(job1Log = ApiResult.Success("build failed"))

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
    fun `pollOnce concatenates logs from all jobs so tag in any job is found`() = runTest {
        val result = makeResult("Successful", "Finished")
        coEvery { apiClient.getLatestResult("PROJ-BUILD") } returns ApiResult.Success(result)
        // Docker tag only in JOB2 (e.g. Docker Build stage), not in JOB1 (Compile stage)
        mockJobLogs(
            job1Log = ApiResult.Success("Compiling source..."),
            job2Log = ApiResult.Success("Publishing image\nUnique Docker Tag : feature-abc-789\nDone.")
        )

        val service = BuildMonitorService(apiClient, eventBus, this)

        eventBus.events.test {
            service.pollOnce("PROJ-BUILD", "main")

            val event = awaitItem() as WorkflowEvent.BuildLogReady
            // Concatenated log must contain content from both jobs
            assertTrue(event.logText.contains("Compiling source..."))
            assertTrue(event.logText.contains("Unique Docker Tag : feature-abc-789"))

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
            stages = BambooStageCollection(stage = emptyList())
        )
        coEvery { apiClient.getLatestResult("PROJ-BUILD") } returns ApiResult.Success(result)
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
    fun `pollOnce caches BuildLogReady in BuildLogCache for late-subscribing readers`() = runTest {
        // Backstop for EventBus replay=0: panels that mount AFTER the poll fires
        // (Automation tab opened cold) must still be able to read the latest log.
        val result = makeResult("Successful", "Finished")
        coEvery { apiClient.getLatestResult("PROJ-BUILD") } returns ApiResult.Success(result)
        mockJobLogs(
            job1Log = ApiResult.Success("Compiling..."),
            job2Log = ApiResult.Success("Unique Docker Tag : my-tag-123")
        )

        val service = BuildMonitorService(apiClient, eventBus, this, buildLogCache = buildLogCache)
        service.pollOnce("PROJ-BUILD", "main")

        val cached = buildLogCache.getLatest("PROJ-BUILD")
        assertNotNull(cached)
        assertEquals("PROJ-BUILD", cached!!.planKey)
        assertEquals(42, cached.buildNumber)
        assertEquals(WorkflowEvent.BuildEventStatus.SUCCESS, cached.status)
        assertTrue(cached.logText.contains("Unique Docker Tag : my-tag-123"))
    }

    @Test
    fun `pollOnce sets chainKey equal to planKey on BuildLogReady`() = runTest {
        // Phase E: chainKey is always populated — equals planKey when the monitor
        // was started with the chain key (post-autoDetectPlan resolution).
        val result = makeResult("Successful", "Finished")
        coEvery { apiClient.getLatestResult("PROJ-BUILD523") } returns ApiResult.Success(result.copy(key = "PROJ-BUILD523-42"))
        mockJobLogs(buildNumber = 42, job1Log = ApiResult.Success("Unique Docker Tag : feature-tag-xyz"))

        val service = BuildMonitorService(apiClient, eventBus, this)

        eventBus.events.test {
            service.pollOnce("PROJ-BUILD523", "main")

            val event = awaitItem() as WorkflowEvent.BuildLogReady
            assertEquals("PROJ-BUILD523", event.chainKey)
            assertEquals("PROJ-BUILD523", event.planKey)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `pollOnce handles API error gracefully`() = runTest {
        coEvery { apiClient.getLatestResult("PROJ-BUILD") } returns
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

    // BAMBOO-COV-10: newer-build detection path

    @Test
    fun `pollOnce populates newerBuild when a higher-numbered build is running`() = runTest {
        // Current finished build is #42; build #43 is InProgress.
        val result = makeResult("Successful", "Finished", buildNumber = 42)
        coEvery { apiClient.getLatestResult("PROJ-BUILD") } returns ApiResult.Success(result)
        mockJobLogs(buildNumber = 42, job1Log = ApiResult.Success("log"))

        // Override the default stub: return a build with a higher number.
        coEvery { apiClient.getRunningAndQueuedBuilds("PROJ-BUILD") } returns ApiResult.Success(
            listOf(
                BambooResultDto(
                    key = "PROJ-BUILD-43",
                    buildNumber = 43,
                    state = "Unknown",
                    lifeCycleState = "InProgress"
                )
            )
        )

        val service = BuildMonitorService(apiClient, eventBus, this)
        service.pollOnce("PROJ-BUILD", "main")

        val state = service.stateFlow.value
        assertNotNull(state, "stateFlow should have a non-null value after poll")
        assertNotNull(state!!.newerBuild,
            "newerBuild should be populated when build #43 is running")
        assertEquals(43, state.newerBuild!!.buildNumber,
            "newerBuild.buildNumber should be 43")
    }

    @Test
    fun `pollOnce leaves newerBuild null when no higher-numbered build exists`() = runTest {
        val result = makeResult("Successful", "Finished", buildNumber = 42)
        coEvery { apiClient.getLatestResult("PROJ-BUILD") } returns ApiResult.Success(result)
        mockJobLogs(buildNumber = 42, job1Log = ApiResult.Success("log"))

        // Explicitly return empty list (matches the default stub but is explicitly documented here).
        coEvery { apiClient.getRunningAndQueuedBuilds("PROJ-BUILD") } returns ApiResult.Success(emptyList())

        val service = BuildMonitorService(apiClient, eventBus, this)
        service.pollOnce("PROJ-BUILD", "main")

        val state = service.stateFlow.value
        assertNotNull(state)
        assertNull(state!!.newerBuild,
            "newerBuild should be null when no higher-numbered build is running")
    }

    // BAMBOO-COV-11: first-poll BuildFinished suppression

    @Test
    fun `pollOnce does not emit BuildFinished on the very first terminal poll`() = runTest {
        // The !isFirstPoll guard at BuildMonitorService.kt prevents stale "Build Failed"
        // notifications on IDE startup. This test pins that guard: removing it would
        // cause an immediate BuildFinished event, failing this test.
        val result = makeResult("Successful", "Finished")
        coEvery { apiClient.getLatestResult("PROJ-BUILD") } returns ApiResult.Success(result)
        mockJobLogs(job1Log = ApiResult.Success("Compiling..."))

        val service = BuildMonitorService(apiClient, eventBus, this)

        eventBus.events.test {
            service.pollOnce("PROJ-BUILD", "main")

            // BuildLogReady IS emitted on first poll; BuildFinished must NOT be.
            val event = awaitItem()
            assertTrue(event is WorkflowEvent.BuildLogReady,
                "First event must be BuildLogReady, got: $event")

            // No further events on first poll — specifically, no BuildFinished.
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

}

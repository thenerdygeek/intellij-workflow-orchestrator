package com.workflow.orchestrator.agent.monitor

import com.workflow.orchestrator.core.events.WorkflowEvent
import com.workflow.orchestrator.core.model.bitbucket.PullRequestDetailData
import com.workflow.orchestrator.core.services.BitbucketService
import com.workflow.orchestrator.core.services.ToolResult
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PrStateSourceTest {

    private val watchedPrId = 42
    private val monitorId = "pr-state-test"

    private fun makeBitbucket() = mockk<BitbucketService>()

    private fun makeDetail(state: String) = PullRequestDetailData(
        id = watchedPrId,
        title = "Test PR",
        description = null,
        state = state,
        fromBranch = "feature/foo",
        toBranch = "main",
        authorName = "author",
        reviewers = emptyList(),
        createdDate = 0L,
        updatedDate = 0L,
        version = 1,
    )

    private fun okDetailResult(state: String) = ToolResult(
        data = makeDetail(state),
        summary = "ok",
        isError = false,
    )

    private fun errDetailResult() = ToolResult<PullRequestDetailData>(
        data = null,
        summary = "error",
        isError = true,
    )

    // ------------------------------------------------------------------ companion classify tests

    @Test
    fun `classify - PullRequestMerged for watched prId returns NOTABLE`() {
        val event = WorkflowEvent.PullRequestMerged(prId = watchedPrId)
        val result = PrStateSource.classify(monitorId, watchedPrId, event)
        assertEquals(Severity.NOTABLE, result?.severity)
        assertTrue(result?.line?.contains("merged") == true)
    }

    @Test
    fun `classify - PullRequestMerged for different prId returns null`() {
        val event = WorkflowEvent.PullRequestMerged(prId = 999)
        val result = PrStateSource.classify(monitorId, watchedPrId, event)
        assertNull(result)
    }

    @Test
    fun `classify - PullRequestDeclined for watched prId returns ALERT`() {
        val event = WorkflowEvent.PullRequestDeclined(prId = watchedPrId)
        val result = PrStateSource.classify(monitorId, watchedPrId, event)
        assertEquals(Severity.ALERT, result?.severity)
        assertTrue(result?.line?.contains("declined") == true)
    }

    @Test
    fun `classify - PullRequestDeclined for different prId returns null`() {
        val event = WorkflowEvent.PullRequestDeclined(prId = 1)
        val result = PrStateSource.classify(monitorId, watchedPrId, event)
        assertNull(result)
    }

    @Test
    fun `classify - PullRequestApproved for watched prId returns NOTABLE with byUser`() {
        val event = WorkflowEvent.PullRequestApproved(prId = watchedPrId, byUser = "alice")
        val result = PrStateSource.classify(monitorId, watchedPrId, event)
        assertEquals(Severity.NOTABLE, result?.severity)
        assertTrue(result?.line?.contains("alice") == true, "line should contain byUser name")
    }

    @Test
    fun `classify - PullRequestApproved for different prId returns null`() {
        val event = WorkflowEvent.PullRequestApproved(prId = 5, byUser = "bob")
        val result = PrStateSource.classify(monitorId, watchedPrId, event)
        assertNull(result)
    }

    @Test
    fun `classify - unrelated WorkflowEvent returns null`() {
        val event = WorkflowEvent.TicketChanged("PROJ-1", "Some ticket")
        val result = PrStateSource.classify(monitorId, watchedPrId, event)
        assertNull(result)
    }

    @Test
    fun `classify - BuildFinished unrelated event returns null`() {
        val event = WorkflowEvent.BuildFinished("MY-PLAN", 1, WorkflowEvent.BuildEventStatus.SUCCESS)
        val result = PrStateSource.classify(monitorId, watchedPrId, event)
        assertNull(result)
    }

    // ------------------------------------------------------------------ hydrate tests

    @Test
    fun `hydrate - state MERGED returns NOTABLE synthetic event`() =
        runTest(UnconfinedTestDispatcher()) {
            val bitbucket = makeBitbucket()
            coEvery { bitbucket.getPullRequestDetail(watchedPrId, null) } returns okDetailResult("MERGED")

            val flow = MutableSharedFlow<WorkflowEvent>(extraBufferCapacity = 8)
            val source = PrStateSource(
                monitorId = monitorId,
                description = "test",
                cs = this,
                flow = flow,
                bitbucket = bitbucket,
                prId = watchedPrId,
                repoName = null,
            )

            val sink = mutableListOf<MonitorEvent>()
            source.start { sink.add(it) }
            testScheduler.advanceUntilIdle()
            source.stop()

            assertEquals(1, sink.size)
            assertEquals(Severity.NOTABLE, sink[0].severity)
            assertTrue(sink[0].line.contains("MERGED"))
        }

    @Test
    fun `hydrate - state DECLINED returns ALERT synthetic event`() =
        runTest(UnconfinedTestDispatcher()) {
            val bitbucket = makeBitbucket()
            coEvery { bitbucket.getPullRequestDetail(watchedPrId, null) } returns okDetailResult("DECLINED")

            val flow = MutableSharedFlow<WorkflowEvent>(extraBufferCapacity = 8)
            val source = PrStateSource(
                monitorId = monitorId,
                description = "test",
                cs = this,
                flow = flow,
                bitbucket = bitbucket,
                prId = watchedPrId,
                repoName = null,
            )

            val sink = mutableListOf<MonitorEvent>()
            source.start { sink.add(it) }
            testScheduler.advanceUntilIdle()
            source.stop()

            assertEquals(1, sink.size)
            assertEquals(Severity.ALERT, sink[0].severity)
            assertTrue(sink[0].line.contains("DECLINED"))
        }

    @Test
    fun `hydrate - state OPEN returns null (no hydration event)`() =
        runTest(UnconfinedTestDispatcher()) {
            val bitbucket = makeBitbucket()
            coEvery { bitbucket.getPullRequestDetail(watchedPrId, null) } returns okDetailResult("OPEN")

            val flow = MutableSharedFlow<WorkflowEvent>(extraBufferCapacity = 8)
            val source = PrStateSource(
                monitorId = monitorId,
                description = "test",
                cs = this,
                flow = flow,
                bitbucket = bitbucket,
                prId = watchedPrId,
                repoName = null,
            )

            val sink = mutableListOf<MonitorEvent>()
            source.start { sink.add(it) }
            testScheduler.advanceUntilIdle()
            source.stop()

            assertTrue(sink.isEmpty(), "OPEN state must not emit any hydration event")
        }

    @Test
    fun `hydrate - isError returns null (no hydration event)`() =
        runTest(UnconfinedTestDispatcher()) {
            val bitbucket = makeBitbucket()
            coEvery { bitbucket.getPullRequestDetail(watchedPrId, null) } returns errDetailResult()

            val flow = MutableSharedFlow<WorkflowEvent>(extraBufferCapacity = 8)
            val source = PrStateSource(
                monitorId = monitorId,
                description = "test",
                cs = this,
                flow = flow,
                bitbucket = bitbucket,
                prId = watchedPrId,
                repoName = null,
            )

            val sink = mutableListOf<MonitorEvent>()
            source.start { sink.add(it) }
            testScheduler.advanceUntilIdle()
            source.stop()

            assertTrue(sink.isEmpty(), "Error result must not emit any hydration event")
        }

    @Test
    fun `hydrate - lowercase state merged is treated case-insensitively`() =
        runTest(UnconfinedTestDispatcher()) {
            val bitbucket = makeBitbucket()
            coEvery { bitbucket.getPullRequestDetail(watchedPrId, null) } returns okDetailResult("merged")

            val flow = MutableSharedFlow<WorkflowEvent>(extraBufferCapacity = 8)
            val source = PrStateSource(
                monitorId = monitorId,
                description = "test",
                cs = this,
                flow = flow,
                bitbucket = bitbucket,
                prId = watchedPrId,
                repoName = null,
            )

            val sink = mutableListOf<MonitorEvent>()
            source.start { sink.add(it) }
            source.stop()

            assertEquals(1, sink.size, "lowercase 'merged' should still produce a hydration event")
            assertEquals(Severity.NOTABLE, sink[0].severity)
        }

    // ------------------------------------------------------------------ integration test

    @Test
    fun `integration - start emits hydrate event first then flow PullRequestMerged`() =
        runTest(UnconfinedTestDispatcher()) {
            val bitbucket = makeBitbucket()
            // PR is already DECLINED when we subscribe → hydration event expected first
            coEvery { bitbucket.getPullRequestDetail(watchedPrId, null) } returns okDetailResult("DECLINED")

            val flow = MutableSharedFlow<WorkflowEvent>(extraBufferCapacity = 8)
            val sink = mutableListOf<MonitorEvent>()

            val source = PrStateSource(
                monitorId = monitorId,
                description = "PR #42 state",
                cs = this,
                flow = flow,
                bitbucket = bitbucket,
                prId = watchedPrId,
                repoName = null,
            )

            source.start { sink.add(it) }

            // UnconfinedTestDispatcher runs start() eagerly so hydration already ran.
            // Now emit a flow event.
            flow.emit(WorkflowEvent.PullRequestMerged(prId = watchedPrId))

            source.stop()

            assertEquals(2, sink.size, "Must have hydration event + flow event")
            // Hydration came first
            assertEquals(Severity.ALERT, sink[0].severity, "First event must be the hydration ALERT")
            assertTrue(sink[0].line.contains("DECLINED"))
            // Flow event came second
            assertEquals(Severity.NOTABLE, sink[1].severity, "Second event must be the merged NOTABLE")
            assertTrue(sink[1].line.contains("merged"))
            assertEquals(monitorId, sink[1].monitorId)
        }
}

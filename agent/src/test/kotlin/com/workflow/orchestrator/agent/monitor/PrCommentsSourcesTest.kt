package com.workflow.orchestrator.agent.monitor

import com.workflow.orchestrator.core.events.WorkflowEvent
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
class PrCommentsSourcesTest {

    private val watchedPrId = 42
    private val monitorId = "pr-comments-test"

    private fun makeBitbucket() = mockk<BitbucketService>()

    private fun okCountResult(count: Int) = ToolResult(
        data = count,
        summary = "ok",
        isError = false,
    )

    private fun errCountResult() = ToolResult<Int>(
        data = null,
        summary = "error",
        isError = true,
    )

    // ------------------------------------------------------------------ PrBlockerCountSource: diff tests

    @Test
    fun `diff - first poll count 0 returns empty`() {
        val events = PrBlockerCountSource.diffPure(monitorId, watchedPrId, previous = null, current = 0)
        assertTrue(events.isEmpty(), "count=0 on first poll must not emit any event")
    }

    @Test
    fun `diff - first poll count greater than 0 returns ALERT`() {
        val events = PrBlockerCountSource.diffPure(monitorId, watchedPrId, previous = null, current = 2)
        assertEquals(1, events.size)
        assertEquals(Severity.ALERT, events[0].severity)
        assertTrue(events[0].line.contains("2"), "line should contain count")
        assertTrue(events[0].line.contains("blocker"), "line should mention blocker")
    }

    @Test
    fun `diff - count increases from 0 to 1 returns ALERT`() {
        val events = PrBlockerCountSource.diffPure(monitorId, watchedPrId, previous = 0, current = 1)
        assertEquals(1, events.size)
        assertEquals(Severity.ALERT, events[0].severity)
        assertTrue(events[0].line.contains("1"))
    }

    @Test
    fun `diff - count decreases returns empty`() {
        val events = PrBlockerCountSource.diffPure(monitorId, watchedPrId, previous = 2, current = 1)
        assertTrue(events.isEmpty(), "decrease in blocker count must not emit event")
    }

    @Test
    fun `diff - count unchanged returns empty`() {
        val events = PrBlockerCountSource.diffPure(monitorId, watchedPrId, previous = 3, current = 3)
        assertTrue(events.isEmpty(), "unchanged blocker count must not emit event")
    }

    @Test
    fun `diff - increase includes was-count in message`() {
        val events = PrBlockerCountSource.diffPure(monitorId, watchedPrId, previous = 1, current = 3)
        assertEquals(1, events.size)
        assertEquals(Severity.ALERT, events[0].severity)
        assertTrue(events[0].line.contains("3"), "current count should appear in line")
        assertTrue(events[0].line.contains("1"), "previous count should appear in line (was N)")
    }

    // ------------------------------------------------------------------ PrBlockerCountSource: pollOnce tests

    @Test
    fun `pollOnce - isError returns false and emits no events`() = runTest(UnconfinedTestDispatcher()) {
        val bitbucket = makeBitbucket()
        coEvery { bitbucket.getBlockerCommentsCount(watchedPrId, null) } returns errCountResult()

        val source = PrBlockerCountSource(
            monitorId = monitorId,
            description = "test",
            cs = this,
            bitbucket = bitbucket,
            prId = watchedPrId,
            repoName = null,
        )

        val sink = mutableListOf<MonitorEvent>()
        val changed = source.pollOnce { sink.add(it) }

        assertTrue(!changed, "isError should return false from pollOnce")
        assertTrue(sink.isEmpty(), "isError should emit no events")
    }

    @Test
    fun `pollOnce - first call count 0 emits no events`() = runTest(UnconfinedTestDispatcher()) {
        val bitbucket = makeBitbucket()
        coEvery { bitbucket.getBlockerCommentsCount(watchedPrId, null) } returns okCountResult(0)

        val source = PrBlockerCountSource(
            monitorId = monitorId,
            description = "test",
            cs = this,
            bitbucket = bitbucket,
            prId = watchedPrId,
            repoName = null,
        )

        val sink = mutableListOf<MonitorEvent>()
        val changed = source.pollOnce { sink.add(it) }

        assertTrue(!changed)
        assertTrue(sink.isEmpty())
    }

    @Test
    fun `pollOnce - first call count 2 emits ALERT and returns true`() = runTest(UnconfinedTestDispatcher()) {
        val bitbucket = makeBitbucket()
        coEvery { bitbucket.getBlockerCommentsCount(watchedPrId, null) } returns okCountResult(2)

        val source = PrBlockerCountSource(
            monitorId = monitorId,
            description = "test",
            cs = this,
            bitbucket = bitbucket,
            prId = watchedPrId,
            repoName = null,
        )

        val sink = mutableListOf<MonitorEvent>()
        val changed = source.pollOnce { sink.add(it) }

        assertTrue(changed)
        assertEquals(1, sink.size)
        assertEquals(Severity.ALERT, sink[0].severity)
        assertEquals(monitorId, sink[0].monitorId)
    }

    @Test
    fun `pollOnce - with repoName passes it to service`() = runTest(UnconfinedTestDispatcher()) {
        val bitbucket = makeBitbucket()
        val repoName = "my-repo"
        coEvery { bitbucket.getBlockerCommentsCount(watchedPrId, repoName) } returns okCountResult(1)

        val source = PrBlockerCountSource(
            monitorId = monitorId,
            description = "test",
            cs = this,
            bitbucket = bitbucket,
            prId = watchedPrId,
            repoName = repoName,
        )

        val sink = mutableListOf<MonitorEvent>()
        source.pollOnce { sink.add(it) }

        assertEquals(1, sink.size)
    }

    // ------------------------------------------------------------------ PrCommentsTotalSource: companion classify tests

    @Test
    fun `classify - PrCommentsUpdated for watched prId returns NOTABLE with total`() {
        val event = WorkflowEvent.PrCommentsUpdated(
            projectKey = "PROJ",
            repoSlug = "repo",
            prId = watchedPrId,
            total = 5,
            unreadCount = 0,
        )
        val result = PrCommentsTotalSource.classify(monitorId, watchedPrId, event)
        assertEquals(Severity.NOTABLE, result?.severity)
        assertTrue(result?.line?.contains("5") == true, "line should contain total count")
    }

    @Test
    fun `classify - PrCommentsUpdated for different prId returns null`() {
        val event = WorkflowEvent.PrCommentsUpdated(
            projectKey = "PROJ",
            repoSlug = "repo",
            prId = 999,
            total = 5,
            unreadCount = 0,
        )
        val result = PrCommentsTotalSource.classify(monitorId, watchedPrId, event)
        assertNull(result)
    }

    @Test
    fun `classify - unrelated WorkflowEvent returns null`() {
        val event = WorkflowEvent.TicketChanged("PROJ-1", "Some ticket")
        val result = PrCommentsTotalSource.classify(monitorId, watchedPrId, event)
        assertNull(result)
    }

    @Test
    fun `classify - PullRequestMerged returns null (different event type)`() {
        val event = WorkflowEvent.PullRequestMerged(prId = watchedPrId)
        val result = PrCommentsTotalSource.classify(monitorId, watchedPrId, event)
        assertNull(result)
    }

    @Test
    fun `classify - monitorId is stamped onto returned event`() {
        val event = WorkflowEvent.PrCommentsUpdated(
            projectKey = "PROJ",
            repoSlug = "repo",
            prId = watchedPrId,
            total = 3,
            unreadCount = 0,
        )
        val result = PrCommentsTotalSource.classify(monitorId, watchedPrId, event)
        assertEquals(monitorId, result?.monitorId)
    }

    // ------------------------------------------------------------------ PrCommentsTotalSource: flow integration test

    @Test
    fun `integration - PrCommentsUpdated event for watched prId is emitted`() =
        runTest(UnconfinedTestDispatcher()) {
            val flow = MutableSharedFlow<WorkflowEvent>(extraBufferCapacity = 8)
            val sink = mutableListOf<MonitorEvent>()

            val source = PrCommentsTotalSource(
                monitorId = monitorId,
                description = "PR #42 comments",
                cs = this,
                flow = flow,
                prId = watchedPrId,
            )

            source.start { sink.add(it) }

            flow.emit(
                WorkflowEvent.PrCommentsUpdated(
                    projectKey = "PROJ",
                    repoSlug = "repo",
                    prId = watchedPrId,
                    total = 7,
                    unreadCount = 0,
                ),
            )

            source.stop()

            assertEquals(1, sink.size)
            assertEquals(Severity.NOTABLE, sink[0].severity)
            assertTrue(sink[0].line.contains("7"), "line must contain total count")
            assertEquals(monitorId, sink[0].monitorId)
        }

    @Test
    fun `integration - PrCommentsUpdated for different prId is ignored`() =
        runTest(UnconfinedTestDispatcher()) {
            val flow = MutableSharedFlow<WorkflowEvent>(extraBufferCapacity = 8)
            val sink = mutableListOf<MonitorEvent>()

            val source = PrCommentsTotalSource(
                monitorId = monitorId,
                description = "PR #42 comments",
                cs = this,
                flow = flow,
                prId = watchedPrId,
            )

            source.start { sink.add(it) }

            flow.emit(
                WorkflowEvent.PrCommentsUpdated(
                    projectKey = "PROJ",
                    repoSlug = "repo",
                    prId = 9999,
                    total = 7,
                    unreadCount = 0,
                ),
            )

            source.stop()

            assertTrue(sink.isEmpty(), "event for different prId must be ignored")
        }

    @Test
    fun `integration - no hydration event on start (no hydrate override)`() =
        runTest(UnconfinedTestDispatcher()) {
            val flow = MutableSharedFlow<WorkflowEvent>(extraBufferCapacity = 8)
            val sink = mutableListOf<MonitorEvent>()

            val source = PrCommentsTotalSource(
                monitorId = monitorId,
                description = "test",
                cs = this,
                flow = flow,
                prId = watchedPrId,
            )

            source.start { sink.add(it) }
            source.stop()

            assertTrue(sink.isEmpty(), "PrCommentsTotalSource must not emit a hydration event on start")
        }
}

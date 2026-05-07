package com.workflow.orchestrator.core.http

import com.workflow.orchestrator.core.events.WorkflowEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class DevStatusCacheInvalidatorTest {

    @AfterEach
    fun tearDown() {
        HttpResponseCache.invalidateAll()
    }

    @Test
    fun `BranchChanged triggers invalidation`() = runTest {
        val bus = MutableSharedFlow<WorkflowEvent>(extraBufferCapacity = 16)
        val count = AtomicInteger(0)
        // UnconfinedTestDispatcher lets the collector start eagerly (no scheduler tick needed)
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val invalidator = DevStatusCacheInvalidator.testInstance(bus, scope) { count.incrementAndGet() }
        invalidator.start()

        bus.emit(WorkflowEvent.BranchChanged(branchName = "feature/TEST-1"))
        advanceUntilIdle()

        assertEquals(1, count.get())
    }

    @Test
    fun `PullRequestMerged triggers invalidation`() = runTest {
        val bus = MutableSharedFlow<WorkflowEvent>(extraBufferCapacity = 16)
        val count = AtomicInteger(0)
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val invalidator = DevStatusCacheInvalidator.testInstance(bus, scope) { count.incrementAndGet() }
        invalidator.start()

        bus.emit(WorkflowEvent.PullRequestMerged(prId = 42))
        advanceUntilIdle()

        assertEquals(1, count.get())
    }

    @Test
    fun `TicketChanged triggers invalidation`() = runTest {
        val bus = MutableSharedFlow<WorkflowEvent>(extraBufferCapacity = 16)
        val count = AtomicInteger(0)
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val invalidator = DevStatusCacheInvalidator.testInstance(bus, scope) { count.incrementAndGet() }
        invalidator.start()

        bus.emit(WorkflowEvent.TicketChanged(ticketId = "PROJ-1", ticketSummary = "Fix bug"))
        advanceUntilIdle()

        assertEquals(1, count.get())
    }

    @Test
    fun `BuildFinished does NOT trigger invalidation`() = runTest {
        val bus = MutableSharedFlow<WorkflowEvent>(extraBufferCapacity = 16)
        val count = AtomicInteger(0)
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val invalidator = DevStatusCacheInvalidator.testInstance(bus, scope) { count.incrementAndGet() }
        invalidator.start()

        bus.emit(WorkflowEvent.BuildFinished(planKey = "PROJ-PLAN", buildNumber = 10, status = WorkflowEvent.BuildEventStatus.SUCCESS))
        advanceUntilIdle()

        assertEquals(0, count.get())
    }

    @Test
    fun `JiraCommentPosted does NOT trigger invalidation`() = runTest {
        val bus = MutableSharedFlow<WorkflowEvent>(extraBufferCapacity = 16)
        val count = AtomicInteger(0)
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val invalidator = DevStatusCacheInvalidator.testInstance(bus, scope) { count.incrementAndGet() }
        invalidator.start()

        bus.emit(WorkflowEvent.JiraCommentPosted(ticketId = "PROJ-1", commentId = "1001"))
        advanceUntilIdle()

        assertEquals(0, count.get())
    }
}

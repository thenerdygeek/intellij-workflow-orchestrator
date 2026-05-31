package com.workflow.orchestrator.pullrequest.ui

import com.workflow.orchestrator.core.events.EventBus
import com.workflow.orchestrator.core.events.WorkflowEvent
import com.workflow.orchestrator.core.model.PrComment
import com.workflow.orchestrator.core.model.PrCommentAuthor
import com.workflow.orchestrator.core.model.PrCommentSeverity
import com.workflow.orchestrator.core.model.PrCommentState
import com.workflow.orchestrator.core.services.BitbucketService
import com.workflow.orchestrator.core.services.ToolResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CommentsViewModelTest {

    private fun makeComment(
        id: String = "1",
        text: String = "hi",
        state: PrCommentState = PrCommentState.OPEN,
    ): PrComment = PrComment(
        id = id, version = 0, text = text,
        author = PrCommentAuthor("u", "U"),
        createdDate = 1L, updatedDate = 1L,
        state = state, severity = PrCommentSeverity.NORMAL,
    )

    @Test
    fun `refresh loads comments from service`() = runTest {
        val service = mockk<BitbucketService>()
        coEvery { service.listPrComments("P", "R", 1, false, false) } returns
            ToolResult.success(listOf(makeComment("1"), makeComment("2")), summary = "2")
        val vm = CommentsViewModel(service, "P", "R", 1)
        vm.refresh()
        assertEquals(2, vm.comments.size)
        assertNull(vm.lastError)
    }

    @Test
    fun `refresh surfaces service error`() = runTest {
        val service = mockk<BitbucketService>()
        coEvery { service.listPrComments("P", "R", 1, false, false) } returns
            ToolResult(data = emptyList(), summary = "auth failed", isError = true)
        val vm = CommentsViewModel(service, "P", "R", 1)
        vm.refresh()
        assertEquals(0, vm.comments.size)
        assertEquals("auth failed", vm.lastError)
    }

    @Test
    fun `postGeneral calls service addPrComment then refreshes`() = runTest {
        val service = mockk<BitbucketService>()
        coEvery { service.addPrComment(1, "hello", null) } returns
            ToolResult.success(Unit, summary = "posted")
        coEvery { service.listPrComments("P", "R", 1, false, false) } returns
            ToolResult.success(listOf(makeComment("99", "hello")), summary = "1")
        val vm = CommentsViewModel(service, "P", "R", 1)
        val ok = vm.postGeneralComment("hello")
        assertTrue(ok)
        assertEquals(1, vm.comments.size)
        coVerify { service.addPrComment(1, "hello", null) }
    }

    @Test
    fun `resolve calls service resolve then refreshes`() = runTest {
        val service = mockk<BitbucketService>()
        coEvery { service.resolvePrComment("P", "R", 1, 99L) } returns
            ToolResult.success(makeComment("99", "x", state = PrCommentState.RESOLVED), summary = "resolved")
        coEvery { service.listPrComments("P", "R", 1, false, false) } returns
            ToolResult.success(listOf(makeComment("99", "x", state = PrCommentState.RESOLVED)), summary = "1")
        val vm = CommentsViewModel(service, "P", "R", 1)
        val ok = vm.resolve(99L)
        assertTrue(ok)
        assertEquals(PrCommentState.RESOLVED, vm.comments[0].state)
    }

    @Test
    fun `reply calls service replyToComment then refreshes`() = runTest {
        val service = mockk<BitbucketService>()
        coEvery { service.replyToComment(1, 50, "ack", null) } returns
            ToolResult.success(Unit, summary = "replied")
        coEvery { service.listPrComments("P", "R", 1, false, false) } returns
            ToolResult.success(
                listOf(makeComment("50", "orig"), makeComment("100", "ack")),
                summary = "2",
            )
        val vm = CommentsViewModel(service, "P", "R", 1)
        val ok = vm.reply(50L, "ack")
        assertTrue(ok)
        assertEquals(2, vm.comments.size)
    }

    @Test
    fun `refresh publishes PrCommentsUpdated event`() = runTest {
        val service = mockk<BitbucketService>()
        coEvery { service.listPrComments("P", "R", 1, false, false) } returns
            ToolResult.success(listOf(makeComment("1")), summary = "1")

        val bus = EventBus()
        var captured: WorkflowEvent.PrCommentsUpdated? = null
        val collectorJob = launch {
            bus.events.collect { event ->
                if (event is WorkflowEvent.PrCommentsUpdated) captured = event
            }
        }
        // Let the collector subscribe before emitting
        testScheduler.advanceUntilIdle()

        CommentsViewModel(service, "P", "R", 1, eventBus = bus).refresh()
        testScheduler.advanceUntilIdle()

        collectorJob.cancel()

        assertNotNull(captured)
        assertEquals(1, captured!!.total)
        assertEquals(0, captured!!.unreadCount)
        assertEquals("P", captured!!.projectKey)
        assertEquals("R", captured!!.repoSlug)
        assertEquals(1, captured!!.prId)
    }

    // ── PULLREQUEST-COV-2: error paths + reopen ───────────────────────────────

    @Test
    fun `postGeneralComment returns false and sets lastError when service returns error`() = runTest {
        val service = mockk<BitbucketService>()
        coEvery { service.addPrComment(1, "hello", null) } returns
            ToolResult(data = Unit, summary = "auth failed", isError = true)
        val vm = CommentsViewModel(service, "P", "R", 1)

        val ok = vm.postGeneralComment("hello")

        assertFalse(ok, "postGeneralComment must return false on service error")
        assertEquals("auth failed", vm.lastError, "lastError must be set to the service error summary")
        assertEquals(0, vm.comments.size, "comments must not be modified on error")
    }

    @Test
    fun `reply returns false and sets lastError when service returns error`() = runTest {
        val service = mockk<BitbucketService>()
        coEvery { service.replyToComment(1, 50, "ack", null) } returns
            ToolResult(data = Unit, summary = "PR is merged", isError = true)
        val vm = CommentsViewModel(service, "P", "R", 1)

        val ok = vm.reply(50L, "ack")

        assertFalse(ok, "reply must return false on service error")
        assertEquals("PR is merged", vm.lastError)
    }

    @Test
    fun `resolve returns false and sets lastError when service returns error`() = runTest {
        val service = mockk<BitbucketService>()
        coEvery { service.resolvePrComment("P", "R", 1, 99L) } returns
            ToolResult(data = makeComment("99", state = PrCommentState.OPEN), summary = "403 forbidden", isError = true)
        val vm = CommentsViewModel(service, "P", "R", 1)

        val ok = vm.resolve(99L)

        assertFalse(ok, "resolve must return false on service error")
        assertEquals("403 forbidden", vm.lastError)
    }

    @Test
    fun `reopen calls service reopenPrComment and refreshes on success`() = runTest {
        val service = mockk<BitbucketService>()
        coEvery { service.reopenPrComment("P", "R", 1, 77L) } returns
            ToolResult.success(makeComment("77", state = PrCommentState.OPEN), summary = "reopened")
        coEvery { service.listPrComments("P", "R", 1, false, false) } returns
            ToolResult.success(listOf(makeComment("77", state = PrCommentState.OPEN)), summary = "1")
        val vm = CommentsViewModel(service, "P", "R", 1)

        val ok = vm.reopen(77L)

        assertTrue(ok, "reopen must return true on success")
        assertEquals(1, vm.comments.size, "comments list must be refreshed after reopen")
        coVerify { service.reopenPrComment("P", "R", 1, 77L) }
    }

    @Test
    fun `reopen returns false and sets lastError when service returns error`() = runTest {
        val service = mockk<BitbucketService>()
        coEvery { service.reopenPrComment("P", "R", 1, 77L) } returns
            ToolResult(data = makeComment("77"), summary = "not transitionable", isError = true)
        val vm = CommentsViewModel(service, "P", "R", 1)

        val ok = vm.reopen(77L)

        assertFalse(ok, "reopen must return false on service error")
        assertEquals("not transitionable", vm.lastError)
    }

    // ── F-10 Mutex concurrency guard ──────────────────────────────────────────

    @Test
    fun `concurrent refresh calls do not lose comments and do not throw`() = runTest {
        val service = mockk<BitbucketService>()
        coEvery { service.listPrComments("P", "R", 1, false, false) } returns
            ToolResult.success(listOf(makeComment("c1"), makeComment("c2"), makeComment("c3")), summary = "3")
        val vm = CommentsViewModel(service, "P", "R", 1)

        // Launch many concurrent refresh calls; none should throw or corrupt state
        val jobs = (1..20).map {
            launch { vm.refresh() }
        }
        jobs.forEach { it.join() }

        // After all refreshes, the snapshot must be a coherent list of exactly 3 comments
        val snap = vm.comments
        assertEquals(3, snap.size, "comments snapshot should contain exactly 3 items after concurrent refreshes")
        assertTrue(snap.all { it.id in listOf("c1", "c2", "c3") }, "all IDs should be from the mock result")
    }
}

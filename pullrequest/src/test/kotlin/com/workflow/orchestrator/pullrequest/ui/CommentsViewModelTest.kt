package com.workflow.orchestrator.pullrequest.ui

import com.workflow.orchestrator.core.model.PrComment
import com.workflow.orchestrator.core.model.PrCommentAuthor
import com.workflow.orchestrator.core.model.PrCommentSeverity
import com.workflow.orchestrator.core.model.PrCommentState
import com.workflow.orchestrator.core.services.BitbucketService
import com.workflow.orchestrator.core.services.ToolResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
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
}

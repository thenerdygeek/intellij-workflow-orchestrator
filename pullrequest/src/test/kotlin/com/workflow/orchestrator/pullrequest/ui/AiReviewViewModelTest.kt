package com.workflow.orchestrator.pullrequest.ui

import com.workflow.orchestrator.core.prreview.FindingSeverity
import com.workflow.orchestrator.core.prreview.PrReviewFinding
import com.workflow.orchestrator.core.prreview.PrReviewFindingsStore
import com.workflow.orchestrator.core.services.BitbucketService
import com.workflow.orchestrator.core.services.ToolResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class AiReviewViewModelTest {

    private fun sample(id: String = "f-1", file: String? = null, line: Int? = null, pushed: Boolean = false, discarded: Boolean = false) =
        PrReviewFinding(
            id = id,
            prId = "PROJ/repo/PR-1",
            sessionId = "s1",
            file = file,
            lineStart = line,
            severity = FindingSeverity.NORMAL,
            message = "msg",
            pushed = pushed,
            discarded = discarded,
            createdAt = 1000,
        )

    @Test
    fun `refresh loads findings from store`() = runTest {
        val store = mockk<PrReviewFindingsStore>()
        val service = mockk<BitbucketService>()
        coEvery { store.list("PROJ/repo/PR-1", "s1", false) } returns
            ToolResult.success(listOf(sample(), sample("f-2")), summary = "2")
        val vm = AiReviewViewModel(store, service, "PROJ", "repo", 1, "s1")
        vm.refresh()
        assertEquals(2, vm.findings.size)
    }

    @Test
    fun `pushFinding general posts addPrComment and marks pushed`() = runTest {
        val store = mockk<PrReviewFindingsStore>()
        val service = mockk<BitbucketService>()
        val finding = sample(id = "f-general")
        coEvery { service.addPrComment(1, "msg", null) } returns
            ToolResult.success(Unit, summary = "posted")
        coEvery { store.markPushed("f-general", "", any()) } returns ToolResult.success(Unit, summary = "")
        coEvery { store.list(any(), any(), any()) } returns ToolResult.success(emptyList(), summary = "0")
        val vm = AiReviewViewModel(store, service, "PROJ", "repo", 1, "s1")
        val ok = vm.pushFinding(finding)
        assertTrue(ok)
        coVerify { service.addPrComment(1, "msg", null) }
        coVerify { store.markPushed("f-general", "", any()) }
    }

    @Test
    fun `pushFinding inline uses addInlineComment`() = runTest {
        val store = mockk<PrReviewFindingsStore>()
        val service = mockk<BitbucketService>()
        val finding = sample(id = "f-inline", file = "src/Foo.kt", line = 42)
        coEvery {
            service.addInlineComment(1, "src/Foo.kt", 42, "ADDED", "msg", null)
        } returns ToolResult.success(Unit, summary = "posted inline")
        coEvery { store.markPushed("f-inline", "", any()) } returns ToolResult.success(Unit, summary = "")
        coEvery { store.list(any(), any(), any()) } returns ToolResult.success(emptyList(), summary = "0")
        val vm = AiReviewViewModel(store, service, "PROJ", "repo", 1, "s1")
        val ok = vm.pushFinding(finding)
        assertTrue(ok)
        coVerify { service.addInlineComment(1, "src/Foo.kt", 42, "ADDED", "msg", null) }
    }

    @Test
    fun `discard marks finding discarded in store`() = runTest {
        val store = mockk<PrReviewFindingsStore>()
        val service = mockk<BitbucketService>()
        coEvery { store.discard("f-1") } returns ToolResult.success(Unit, summary = "")
        coEvery { store.list(any(), any(), any()) } returns ToolResult.success(emptyList(), summary = "0")
        val vm = AiReviewViewModel(store, service, "PROJ", "repo", 1, "s1")
        vm.discard("f-1")
        coVerify { store.discard("f-1") }
    }
}

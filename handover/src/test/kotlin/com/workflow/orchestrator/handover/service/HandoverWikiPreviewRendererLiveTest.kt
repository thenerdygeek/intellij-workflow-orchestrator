package com.workflow.orchestrator.handover.service

import com.workflow.orchestrator.core.notifications.WorkflowNotificationService
import com.workflow.orchestrator.core.services.JiraService
import com.workflow.orchestrator.core.services.ToolResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class HandoverWikiPreviewRendererLiveTest {

    private val jira: JiraService = mockk()
    private val notifications: WorkflowNotificationService = mockk(relaxed = true)

    private fun newRenderer(scope: CoroutineScope) =
        HandoverWikiPreviewRendererService.forTest(jira = jira, notifications = notifications, cs = scope)

    @Test
    fun `renderImmediate returns LOCAL when cache empty`() = runTest {
        val r = newRenderer(this)
        val res = r.renderImmediate("h2. Hi")
        assertEquals(HandoverWikiPreviewRendererService.Source.LOCAL, res.source)
        assertEquals("<h2>Hi</h2>", res.html)
    }

    @Test
    fun `requestLive populates cache and renderImmediate then returns LIVE_CACHED`() = runTest {
        coEvery { jira.renderWikiMarkup("h2. Hi", "AFTER8TE-912") } returns
            ToolResult.success(data = "<h2 class=jira>Hi</h2>", summary = "ok")

        val r = newRenderer(this)
        r.requestLive("h2. Hi", "AFTER8TE-912")
        advanceUntilIdle()

        val res = r.renderImmediate("h2. Hi")
        assertEquals(HandoverWikiPreviewRendererService.Source.LIVE_CACHED, res.source)
        assertEquals("<h2 class=jira>Hi</h2>", res.html)
    }

    @Test
    fun `liveResults emits LIVE_FRESH on successful fetch`() = runTest {
        coEvery { jira.renderWikiMarkup("h2. Hi", "AFTER8TE-912") } returns
            ToolResult.success(data = "<h2 class=jira>Hi</h2>", summary = "ok")
        val r = newRenderer(this)

        r.requestLive("h2. Hi", "AFTER8TE-912")
        val (text, result) = r.liveResults.first()

        assertEquals("h2. Hi", text)
        assertEquals(HandoverWikiPreviewRendererService.Source.LIVE_FRESH, result.source)
    }

    @Test
    fun `live failure flips isLiveAvailable to false and notifies once`() = runTest {
        coEvery { jira.renderWikiMarkup(any(), any()) } returns
            ToolResult.error<String>(summary = "401 Unauthorized")
        val r = newRenderer(this)

        r.requestLive("h2. Hi", "AFTER8TE-912")
        advanceUntilIdle()
        r.requestLive("h2. Hi 2", "AFTER8TE-912")
        advanceUntilIdle()

        assertFalse(r.isLiveAvailable())
        coVerify(exactly = 1) { notifications.notifyWarning(any(), any(), any()) }
    }

    @Test
    fun `requestLive is no-op when live unavailable`() = runTest {
        coEvery { jira.renderWikiMarkup(any(), any()) } returns ToolResult.error<String>(summary = "401")
        val r = newRenderer(this)

        r.requestLive("h2. Hi", "AFTER8TE-912")
        advanceUntilIdle()

        r.requestLive("h2. Hi 2", "AFTER8TE-912")
        advanceUntilIdle()

        coVerify(exactly = 1) { jira.renderWikiMarkup(any(), any()) }
    }

    @Test
    fun `concurrent requestLive calls for same text fire only one HTTP request`() =
        runTest(UnconfinedTestDispatcher()) {
            coEvery { jira.renderWikiMarkup(any(), any()) } returns
                ToolResult.success(data = "<h2>x</h2>", summary = "ok")

            val r = newRenderer(this)
            repeat(5) { r.requestLive("h2. hi", "AFTER8TE-912") }
            advanceUntilIdle()

            coVerify(exactly = 1) { jira.renderWikiMarkup("h2. hi", "AFTER8TE-912") }
        }

    @Test
    fun `requestLive for different text fires separate HTTP requests`() =
        runTest(UnconfinedTestDispatcher()) {
            coEvery { jira.renderWikiMarkup(any(), any()) } returns
                ToolResult.success(data = "<h2>x</h2>", summary = "ok")

            val r = newRenderer(this)
            r.requestLive("h2. one", "AFTER8TE-912")
            r.requestLive("h2. two", "AFTER8TE-912")
            advanceUntilIdle()

            coVerify(exactly = 1) { jira.renderWikiMarkup("h2. one", "AFTER8TE-912") }
            coVerify(exactly = 1) { jira.renderWikiMarkup("h2. two", "AFTER8TE-912") }
        }
}

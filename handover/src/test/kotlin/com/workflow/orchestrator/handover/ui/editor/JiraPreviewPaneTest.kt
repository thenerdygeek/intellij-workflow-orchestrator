package com.workflow.orchestrator.handover.ui.editor

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.handover.service.HandoverWikiPreviewRendererService
import com.workflow.orchestrator.handover.service.HandoverWikiPreviewRendererService.Result
import com.workflow.orchestrator.handover.service.HandoverWikiPreviewRendererService.Source
import io.mockk.*
import io.mockk.coEvery
import io.mockk.every
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import javax.swing.JEditorPane

class JiraPreviewPaneTest {

    private val renderer: HandoverWikiPreviewRendererService = mockk()
    private val liveFlow = MutableSharedFlow<Pair<String, Result>>(replay = 0)

    init {
        every { renderer.liveResults } returns liveFlow
    }

    private fun walk(c: java.awt.Container): List<java.awt.Component> = buildList {
        for (i in 0 until c.componentCount) {
            val child = c.getComponent(i)
            add(child)
            if (child is java.awt.Container) addAll(walk(child))
        }
    }

    private fun newPane(scope: kotlinx.coroutines.CoroutineScope) = JiraPreviewPane(
        project = mockk<Project>(relaxed = true),
        renderer = renderer,
        ticketKeyProvider = { "AFTER8TE-912" },
        cs = scope,
    )

    @Test
    fun `setRenderedMarkup paints initial result and asks for live when available`() = runTest(UnconfinedTestDispatcher()) {
        every { renderer.renderImmediate(any()) } returns Result("<p>local</p>", Source.LOCAL)
        every { renderer.isLiveAvailable() } returns true
        every { renderer.requestLive(any(), any()) } just Runs

        val pane = newPane(this)
        pane.setRenderedMarkup("h2. hi")
        advanceUntilIdle()

        val editor = walk(pane).filterIsInstance<JEditorPane>().first()
        assertTrue(editor.text.contains("<p>local</p>"), "expected initial paint, got: ${editor.text}")
        verify { renderer.requestLive("h2. hi", "AFTER8TE-912") }
    }

    @Test
    fun `live result swaps editor html when text matches`() = runTest(UnconfinedTestDispatcher()) {
        every { renderer.renderImmediate(any()) } returns Result("<p>local</p>", Source.LOCAL)
        every { renderer.isLiveAvailable() } returns true
        every { renderer.requestLive(any(), any()) } just Runs

        val pane = newPane(this)
        pane.setRenderedMarkup("h2. hi")
        advanceUntilIdle()

        liveFlow.emit("h2. hi" to Result("<h2>HI</h2>", Source.LIVE_FRESH))
        advanceUntilIdle()

        val editor = walk(pane).filterIsInstance<JEditorPane>().first()
        assertTrue(editor.text.contains("<h2>HI</h2>"), editor.text)
    }

    @Test
    fun `live result does NOT swap editor when text mismatches stale`() = runTest(UnconfinedTestDispatcher()) {
        every { renderer.renderImmediate(any()) } returns Result("<p>v2</p>", Source.LOCAL)
        every { renderer.isLiveAvailable() } returns true
        every { renderer.requestLive(any(), any()) } just Runs

        val pane = newPane(this)
        pane.setRenderedMarkup("v2 text")
        advanceUntilIdle()

        liveFlow.emit("v1 text" to Result("<h2>STALE</h2>", Source.LIVE_FRESH))
        advanceUntilIdle()

        val editor = walk(pane).filterIsInstance<JEditorPane>().first()
        assertFalse(editor.text.contains("STALE"), "stale live result must be ignored: ${editor.text}")
    }

    @Test
    fun `does not call requestLive when live unavailable`() = runTest(UnconfinedTestDispatcher()) {
        every { renderer.renderImmediate(any()) } returns Result("<p>local</p>", Source.LOCAL)
        every { renderer.isLiveAvailable() } returns false

        val pane = newPane(this)
        pane.setRenderedMarkup("hi")
        advanceUntilIdle()

        verify(exactly = 0) { renderer.requestLive(any(), any()) }
    }
}

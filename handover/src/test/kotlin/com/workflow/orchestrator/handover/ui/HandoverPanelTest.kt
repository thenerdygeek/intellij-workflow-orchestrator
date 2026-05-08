package com.workflow.orchestrator.handover.ui

import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBTabbedPane
import com.workflow.orchestrator.handover.model.HandoverState
import com.workflow.orchestrator.handover.model.SuiteResult
import com.workflow.orchestrator.handover.ui.tabs.ActionsTab
import com.workflow.orchestrator.handover.ui.tabs.ChecksTab
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Container
import java.time.Instant
import javax.swing.JPanel

class HandoverPanelTest {

    private fun walk(c: Container): List<java.awt.Component> = buildList {
        for (i in 0 until c.componentCount) {
            val child = c.getComponent(i)
            add(child)
            if (child is Container) addAll(walk(child))
        }
    }

    private fun greenState() = HandoverState(
        ticketId = "AFTER8TE-912",
        qualityGatePassed = true,
        suiteResults = listOf(
            SuiteResult(
                suitePlanKey = "API-SMOKE",
                buildResultKey = "API-SMOKE-1",
                dockerTagsJson = "{}",
                passed = true,
                durationMs = 1000,
                triggeredAt = Instant.now(),
                bambooLink = "",
            )
        )
    )

    private fun redState() = HandoverState(
        ticketId = "AFTER8TE-912",
        qualityGatePassed = false,
    )

    private fun newPanelWithStub(state: MutableStateFlow<HandoverState>): HandoverPanel {
        val project = mockk<Project>(relaxed = true)
        val header = HandoverTicketHeader()
        val banner = HandoverOverrideBanner()
        val checksTab = ChecksTab(project)
        // Use a no-op JPanel for ActionsTab/ShareTab placeholders so tests don't need to
        // wire up CopyrightFixCard/TimeLogCard/ShareTab service graphs.
        val actionsTab = JPanel()
        val shareTab = JPanel()
        val scope = CoroutineScope(UnconfinedTestDispatcher() + SupervisorJob())
        return HandoverPanel.forTest(
            project = project,
            stateFlow = state,
            header = header,
            banner = banner,
            checksTab = checksTab,
            actionsTab = actionsTab,
            shareTab = shareTab,
            scope = scope,
        )
    }

    @Test
    fun `mounts ticket header, override banner, and 3-tab JBTabbedPane`() = runTest {
        val state = MutableStateFlow(greenState())
        val panel = newPanelWithStub(state)
        advanceUntilIdle()

        val children = walk(panel).map { it::class.java.simpleName }
        assertTrue(children.any { it.contains("HandoverTicketHeader") }, "missing header: $children")
        assertTrue(children.any { it.contains("HandoverOverrideBanner") }, "missing banner: $children")
        assertTrue(children.any { it.contains("JBTabbedPane") }, "missing tabbed pane: $children")

        val tabs = walk(panel).filterIsInstance<JBTabbedPane>().first()
        assertEquals(3, tabs.tabCount)
        assertEquals("Checks", tabs.getTitleAt(0))
        assertEquals("Actions", tabs.getTitleAt(1))
        assertEquals("Share", tabs.getTitleAt(2))
    }

    @Test
    fun `default tab is Share when all checks green`() = runTest {
        val state = MutableStateFlow(greenState())
        val panel = newPanelWithStub(state)
        advanceUntilIdle()

        val tabs = walk(panel).filterIsInstance<JBTabbedPane>().first()
        assertEquals(2, tabs.selectedIndex, "Share tab is index 2")
    }

    @Test
    fun `default tab is Checks when any check red`() = runTest {
        val state = MutableStateFlow(redState())
        val panel = newPanelWithStub(state)
        advanceUntilIdle()

        val tabs = walk(panel).filterIsInstance<JBTabbedPane>().first()
        assertEquals(0, tabs.selectedIndex, "Checks tab is index 0")
    }

    @Test
    fun `override banner shows when failures non-empty`() = runTest {
        val state = MutableStateFlow(redState())
        val panel = newPanelWithStub(state)
        advanceUntilIdle()

        val banner = walk(panel).filterIsInstance<HandoverOverrideBanner>().first()
        assertTrue(banner.isVisible, "banner should be visible when checks fail")
    }

    @Test
    fun `override banner hidden when all green`() = runTest {
        val state = MutableStateFlow(greenState())
        val panel = newPanelWithStub(state)
        advanceUntilIdle()

        val banner = walk(panel).filterIsInstance<HandoverOverrideBanner>().first()
        assertFalse(banner.isVisible, "banner should be hidden when all checks green")
    }
}

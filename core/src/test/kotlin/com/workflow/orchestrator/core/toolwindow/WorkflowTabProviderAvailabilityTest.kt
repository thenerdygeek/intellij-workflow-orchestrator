package com.workflow.orchestrator.core.toolwindow

import com.intellij.openapi.project.Project
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import javax.swing.JComponent
import javax.swing.JPanel

class WorkflowTabProviderAvailabilityTest {

    private val project = mockk<Project>(relaxed = true)

    private fun provider(title: String, available: Boolean) = object : WorkflowTabProvider {
        override val tabTitle = title
        override val order = 0
        override fun createPanel(project: Project): JComponent = JPanel()
        override fun isAvailable(project: Project) = available
    }

    @Test
    fun `isAvailable defaults to true when not overridden`() {
        val p = object : WorkflowTabProvider {
            override val tabTitle = "X"
            override val order = 0
            override fun createPanel(project: Project): JComponent = JPanel()
        }
        assertTrue(p.isAvailable(project))
    }

    @Test
    fun `isTabAvailable null provider shows, false provider hides`() {
        // A default tab with no matching provider should remain visible.
        assertTrue(WorkflowToolWindowFactory.isTabAvailable(null, project))
        assertTrue(WorkflowToolWindowFactory.isTabAvailable(provider("Sprint", true), project))
        assertEquals(false, WorkflowToolWindowFactory.isTabAvailable(provider("Sprint", false), project))
    }

    @Test
    fun `first visible default tab is promoted to eager when the first is hidden`() {
        // Mirrors buildTabs' selection of the eager tab: filter by availability, take index 0.
        // Guards the bug where hiding Sprint (order 0) left NO eagerly-materialized tab.
        val providers = mapOf(
            "Sprint" to provider("Sprint", false),
            "PR" to provider("PR", true),
        )
        val orderedTitles = listOf("Sprint", "PR")
        val visible = orderedTitles.filter { WorkflowToolWindowFactory.isTabAvailable(providers[it], project) }
        assertEquals(listOf("PR"), visible)
        assertEquals("PR", visible.first(), "PR becomes the eagerly-materialized first tab when Sprint is hidden")
    }
}

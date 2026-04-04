package com.workflow.orchestrator.agent.ui

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.toolwindow.WorkflowTabProvider
import javax.swing.JComponent

/**
 * Tab provider that adds a "History" tab to the Workflow tool window.
 * Shows all past agent sessions. Gap 9: wired to SessionStore.
 */
class HistoryTabProvider : WorkflowTabProvider {

    override val tabId: String = "history"
    override val tabTitle: String = "History"
    override val order: Int = 6

    override fun createPanel(project: Project): JComponent {
        val panel = HistoryPanel(project)
        // Wire resume: find the AgentController from the tool window's agent tab.
        // The HistoryPanel's onResumeSession callback is wired by AgentToolWindowFactory
        // when it has access to the controller. For standalone usage, the callback
        // is set externally.
        return panel
    }
}

package com.workflow.orchestrator.agent.ui

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.toolwindow.WorkflowTabProvider
import javax.swing.JComponent

/**
 * Tab provider that adds a "History" tab to the Workflow tool window.
 *
 * Shows all past agent sessions across all projects (app-level index).
 * Ordered after Agent (5) so it appears as the last tab.
 */
class HistoryTabProvider : WorkflowTabProvider {

    override val tabId: String = "history"
    override val tabTitle: String = "History"
    override val order: Int = 6

    override fun createPanel(project: Project): JComponent {
        return HistoryPanel()
    }
}

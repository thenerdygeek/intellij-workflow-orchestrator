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
        val panel = HistoryPanel()
        panel.onResumeSession = { sessionId ->
            try {
                val controller = com.workflow.orchestrator.agent.AgentService.getInstance(project).activeController
                controller?.resumeSession(sessionId)
                // Switch to Agent tab
                com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
                    .getToolWindow("Workflow")?.let { tw ->
                        tw.contentManager.contents
                            .firstOrNull { it.displayName == "Agent" }
                            ?.let { tw.contentManager.setSelectedContent(it) }
                    }
            } catch (_: Exception) {
                // Controller not available
            }
        }
        return panel
    }
}

package com.workflow.orchestrator.agent.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.workflow.orchestrator.agent.settings.AgentSettings
import com.workflow.orchestrator.core.settings.ConnectionSettings
import com.workflow.orchestrator.core.toolwindow.EmptyStatePanel
import com.workflow.orchestrator.core.toolwindow.WorkflowTabProvider
import javax.swing.JComponent

class AgentTabProvider : WorkflowTabProvider {

    override val tabId: String = "agent"
    override val tabTitle: String = "Agent"
    override val order: Int = 5

    override fun createPanel(project: Project): JComponent {
        val agentSettings = AgentSettings.getInstance(project)
        val connections = ConnectionSettings.getInstance()

        if (!agentSettings.state.agentEnabled) {
            return EmptyStatePanel(
                project,
                "Agent features are disabled.\nEnable them in Settings > Workflow Orchestrator > Agent."
            )
        }

        if (connections.state.sourcegraphUrl.isBlank()) {
            return EmptyStatePanel(
                project,
                "No Sourcegraph connection configured.\nConnect to Cody Enterprise in Settings to use Agent features."
            )
        }

        // Create dashboard with project as parent disposable for JCEF lifecycle
        val dashboard = AgentDashboardPanel(parentDisposable = project as? Disposable)
        val controller = AgentController(project, dashboard)

        // Register controller on AgentService for session resume from History tab
        try { com.workflow.orchestrator.agent.AgentService.getInstance(project).activeController = controller } catch (_: Exception) {}

        // Register controller for disposal
        (project as? Disposable)?.let {
            Disposer.register(it, Disposable { controller.dispose() })
        }

        return dashboard
    }
}

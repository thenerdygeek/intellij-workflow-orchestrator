package com.workflow.orchestrator.core.toolwindow.insights

import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.services.InsightsServiceImpl
import com.workflow.orchestrator.core.services.SessionHistoryReader
import com.workflow.orchestrator.core.toolwindow.EmptyStatePanel
import com.workflow.orchestrator.core.toolwindow.WorkflowTabProvider
import com.workflow.orchestrator.core.util.ProjectIdentifier
import javax.swing.JComponent

class InsightsTabProvider : WorkflowTabProvider {

    override val tabTitle: String = "Insights"
    override val order: Int = 7

    override fun createPanel(project: Project): JComponent {
        val reader = READER_EP.extensionList.firstOrNull()
            ?: return EmptyStatePanel(project, "Agent module not available.\nInsights require the agent module to be active.")

        val baseDir = ProjectIdentifier.agentDir(project.basePath ?: "")
        val service = InsightsServiceImpl(reader, baseDir)
        val panel = InsightsPanel(project, service)
        invokeLater { panel.refresh() }
        return panel
    }

    companion object {
        val READER_EP: ExtensionPointName<SessionHistoryReader> =
            ExtensionPointName.create("com.workflow.orchestrator.sessionHistoryReader")
    }
}

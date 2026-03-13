package com.workflow.orchestrator.sonar.ui

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.toolwindow.EmptyStatePanel
import com.workflow.orchestrator.core.toolwindow.WorkflowTabProvider
import javax.swing.JComponent

class QualityTabProvider : WorkflowTabProvider {

    override val tabId: String = "quality"
    override val tabTitle: String = "Quality"
    override val order: Int = 2

    override fun createPanel(project: Project): JComponent {
        val settings = PluginSettings.getInstance(project)
        return if (!settings.connections.sonarUrl.isNullOrBlank()) {
            QualityDashboardPanel(project)
        } else {
            EmptyStatePanel(project, "No quality data available.\nConnect to SonarQube in Settings to get started.")
        }
    }
}

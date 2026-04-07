package com.workflow.orchestrator.bamboo.ui

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.toolwindow.EmptyStatePanel
import com.workflow.orchestrator.core.toolwindow.WorkflowTabProvider
import javax.swing.JComponent

class BuildTabProvider : WorkflowTabProvider {

    override val tabTitle: String = "Build"
    override val order: Int = 1

    override fun createPanel(project: Project): JComponent {
        val settings = PluginSettings.getInstance(project)
        return if (!settings.connections.bambooUrl.isNullOrBlank()) {
            BuildDashboardPanel(project)
        } else {
            EmptyStatePanel(project, "No builds found.\nConnect to Bamboo in Settings to get started.")
        }
    }
}

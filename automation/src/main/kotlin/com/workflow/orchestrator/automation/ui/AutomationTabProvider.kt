package com.workflow.orchestrator.automation.ui

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.toolwindow.EmptyStatePanel
import com.workflow.orchestrator.core.toolwindow.WorkflowTabProvider
import javax.swing.JComponent

class AutomationTabProvider : WorkflowTabProvider {

    override val tabTitle: String = TAB_TITLE
    override val order: Int = 4

    companion object {
        const val TAB_TITLE = "Automation"
    }

    override fun createPanel(project: Project): JComponent {
        val settings = PluginSettings.getInstance(project)
        return if (!settings.connections.bambooUrl.isNullOrBlank() &&
            settings.state.automationModuleEnabled) {
            AutomationPanel(project)
        } else {
            EmptyStatePanel(
                project,
                "No automation suites configured.\nConnect to Bamboo and configure suites in Settings to get started."
            )
        }
    }
}

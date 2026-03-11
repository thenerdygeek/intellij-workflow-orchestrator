package com.workflow.orchestrator.core.toolwindow

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class WorkflowToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val contentManager = toolWindow.contentManager

        val tabs = listOf(
            TabDefinition("Sprint", "No tickets assigned.\nConnect to Jira in Settings to get started."),
            TabDefinition("Build", "No builds found.\nPush your changes to trigger a CI build."),
            TabDefinition("Quality", "No quality data available.\nConnect to SonarQube in Settings."),
            TabDefinition("Automation", "Automation suite not configured.\nSet up Bamboo in Settings."),
            TabDefinition("Handover", "No active task to hand over.\nStart work on a ticket first.")
        )

        tabs.forEach { tab ->
            val panel = EmptyStatePanel(project, tab.emptyMessage)
            val content = ContentFactory.getInstance().createContent(panel, tab.title, false)
            content.isCloseable = false
            contentManager.addContent(content)
        }
    }

    private data class TabDefinition(val title: String, val emptyMessage: String)
}

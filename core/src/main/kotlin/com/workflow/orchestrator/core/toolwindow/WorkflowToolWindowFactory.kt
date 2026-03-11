package com.workflow.orchestrator.core.toolwindow

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class WorkflowToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val contentManager = toolWindow.contentManager
        val providers = WorkflowTabProvider.EP_NAME.extensionList
            .sortedBy { it.order }
            .associateBy { it.tabTitle }

        val defaultTabs = listOf(
            DefaultTab("Sprint", 0, "No tickets assigned.\nConnect to Jira in Settings to get started."),
            DefaultTab("Build", 1, "No builds found.\nPush your changes to trigger a CI build."),
            DefaultTab("Quality", 2, "No quality data available.\nConnect to SonarQube in Settings."),
            DefaultTab("Automation", 3, "Automation suite not configured.\nSet up Bamboo in Settings."),
            DefaultTab("Handover", 4, "No active task to hand over.\nStart work on a ticket first.")
        )

        defaultTabs.forEach { tab ->
            val provider = providers[tab.title]
            val panel = provider?.createPanel(project)
                ?: EmptyStatePanel(project, tab.emptyMessage)
            val content = ContentFactory.getInstance().createContent(panel, tab.title, false)
            content.isCloseable = false
            contentManager.addContent(content)
        }
    }

    private data class DefaultTab(val title: String, val order: Int, val emptyMessage: String)
}

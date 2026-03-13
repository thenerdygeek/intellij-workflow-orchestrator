package com.workflow.orchestrator.handover.ui

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.toolwindow.EmptyStatePanel
import com.workflow.orchestrator.core.toolwindow.WorkflowTabProvider
import javax.swing.JComponent

class HandoverTabProvider : WorkflowTabProvider {

    override val tabId: String = "handover"
    override val tabTitle: String = "Handover"
    override val order: Int = 4

    override fun createPanel(project: Project): JComponent {
        val settings = PluginSettings.getInstance(project)
        val hasJira = !settings.connections.jiraUrl.isNullOrBlank()
        val hasBitbucket = !settings.connections.bitbucketUrl.isNullOrBlank()

        return if (hasJira || hasBitbucket) {
            HandoverPanel(project)
        } else {
            EmptyStatePanel(
                project,
                "No handover services configured.\nConnect Jira and Bitbucket in Settings to get started."
            )
        }
    }
}

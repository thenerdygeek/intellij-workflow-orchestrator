package com.workflow.orchestrator.pullrequest.ui

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.toolwindow.EmptyStatePanel
import com.workflow.orchestrator.core.toolwindow.WorkflowTabProvider
import javax.swing.JComponent

class PrTabProvider : WorkflowTabProvider {

    override val tabTitle: String = "PR"
    override val order: Int = 2

    override fun createPanel(project: Project): JComponent {
        val settings = PluginSettings.getInstance(project)
        val hasBitbucket = !settings.connections.bitbucketUrl.isNullOrBlank()

        return if (hasBitbucket) {
            PrDashboardPanel(project)
        } else {
            EmptyStatePanel(
                project,
                "No pull request services configured.\nConnect Bitbucket in Settings to get started."
            )
        }
    }
}

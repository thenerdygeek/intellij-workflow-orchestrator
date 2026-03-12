package com.workflow.orchestrator.jira.ui

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.auth.CredentialStore
import com.workflow.orchestrator.core.model.ServiceType
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.toolwindow.EmptyStatePanel
import com.workflow.orchestrator.core.toolwindow.WorkflowTabProvider
import com.workflow.orchestrator.jira.api.JiraApiClient
import com.workflow.orchestrator.jira.service.ActiveTicketService
import com.workflow.orchestrator.jira.service.BranchingService
import com.workflow.orchestrator.jira.service.SprintService
import javax.swing.JComponent

class SprintTabProvider : WorkflowTabProvider {

    override val tabId: String = "sprint"
    override val tabTitle: String = "Sprint"
    override val order: Int = 0

    override fun createPanel(project: Project): JComponent {
        val settings = PluginSettings.getInstance(project)
        val jiraUrl = settings.state.jiraUrl
        if (jiraUrl.isNullOrBlank()) {
            return EmptyStatePanel(project, "No tickets assigned.\nConnect to Jira in Settings to get started.")
        }

        val credentialStore = CredentialStore()
        val apiClient = JiraApiClient(
            baseUrl = jiraUrl.trimEnd('/'),
            tokenProvider = { credentialStore.getToken(ServiceType.JIRA) }
        )
        val sprintService = SprintService(apiClient)
        val activeTicketService = ActiveTicketService()
        val branchingService = BranchingService(project, apiClient, activeTicketService)

        val panel = SprintDashboardPanel(project, sprintService, activeTicketService, branchingService)
        panel.loadData()
        return panel
    }
}

package com.workflow.orchestrator.jira.listeners

import com.intellij.openapi.vcs.BranchChangeListener
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.auth.CredentialStore
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ServiceType
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.jira.api.JiraApiClient
import com.workflow.orchestrator.jira.service.ActiveTicketService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BranchChangeTicketDetector(private val project: Project) : BranchChangeListener {

    private val log = Logger.getInstance(BranchChangeTicketDetector::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun branchWillChange(branchName: String) {
        // No action needed before branch change
    }

    override fun branchHasChanged(branchName: String) {
        val ticketId = ActiveTicketService.extractTicketIdFromBranch(branchName) ?: run {
            log.debug("[Jira:Branch] No ticket ID detected in branch '$branchName'")
            return
        }

        log.info("[Jira:Branch] Detected ticket $ticketId from branch '$branchName'")

        val settings = PluginSettings.getInstance(project)
        val jiraUrl = settings.state.jiraUrl
        if (jiraUrl.isNullOrBlank()) return

        settings.state.activeTicketId = ticketId

        // Fetch ticket summary from Jira in background
        scope.launch {
            val credentialStore = CredentialStore()
            val apiClient = JiraApiClient(
                baseUrl = jiraUrl.trimEnd('/'),
                tokenProvider = { credentialStore.getToken(ServiceType.JIRA) }
            )

            val result = apiClient.getIssue(ticketId)
            if (result is ApiResult.Success) {
                settings.state.activeTicketSummary = result.data.fields.summary
                log.info("[Jira:Branch] Updated active ticket summary for $ticketId")
            }
        }
    }
}

package com.workflow.orchestrator.jira.listeners

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vcs.BranchChangeListener
import com.workflow.orchestrator.core.auth.CredentialStore
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ServiceType
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.jira.api.JiraApiClient
import com.workflow.orchestrator.jira.service.ActiveTicketService
import com.intellij.openapi.application.invokeLater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class BranchChangeTicketDetector(private val project: Project) : BranchChangeListener, Disposable {

    private val log = Logger.getInstance(BranchChangeTicketDetector::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        Disposer.register(project, this)
    }

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
        val jiraUrl = settings.connections.jiraUrl
        if (jiraUrl.isNullOrBlank()) return

        // Write state on EDT since PersistentStateComponent is not thread-safe
        invokeLater {
            settings.state.activeTicketId = ticketId
        }

        // Fetch ticket summary from Jira in background and update shared ActiveTicketService
        scope.launch {
            val credentialStore = CredentialStore()
            val apiClient = JiraApiClient(
                baseUrl = jiraUrl.trimEnd('/'),
                tokenProvider = { credentialStore.getToken(ServiceType.JIRA) }
            )

            val result = apiClient.getIssue(ticketId)
            if (result is ApiResult.Success) {
                val summary = result.data.fields.summary
                invokeLater {
                    settings.state.activeTicketSummary = summary
                }
                ActiveTicketService.getInstance(project).setActiveTicket(ticketId, summary)
                log.info("[Jira:Branch] Updated active ticket summary for $ticketId")
            } else {
                ActiveTicketService.getInstance(project).setActiveTicket(ticketId, "")
            }
        }
    }

    override fun dispose() {
        scope.cancel()
        log.info("[Jira:Branch] BranchChangeTicketDetector disposed")
    }
}

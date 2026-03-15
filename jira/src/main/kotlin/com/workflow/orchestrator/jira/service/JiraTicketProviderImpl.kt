package com.workflow.orchestrator.jira.service

import com.intellij.openapi.diagnostic.Logger
import com.workflow.orchestrator.core.auth.CredentialStore
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ServiceType
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.workflow.JiraTicketProvider
import com.workflow.orchestrator.core.workflow.TicketDetails
import com.workflow.orchestrator.core.workflow.TicketTransition
import kotlinx.coroutines.launch
import com.workflow.orchestrator.core.workflow.WorkflowIntent
import com.workflow.orchestrator.jira.api.JiraApiClient

/**
 * Implementation of [JiraTicketProvider] that delegates to [JiraApiClient].
 * Registered as an extension point so :bamboo can access Jira data without importing :jira.
 */
class JiraTicketProviderImpl : JiraTicketProvider {

    private val log = Logger.getInstance(JiraTicketProviderImpl::class.java)

    private fun createClient(): JiraApiClient? {
        // Use PluginSettings from the default project or any open project
        val projects = com.intellij.openapi.project.ProjectManager.getInstance().openProjects
        val project = projects.firstOrNull() ?: return null
        val settings = PluginSettings.getInstance(project)
        val url = settings.connections.jiraUrl.orEmpty().trimEnd('/')
        if (url.isBlank()) return null
        val credentialStore = CredentialStore()
        return JiraApiClient(
            baseUrl = url,
            tokenProvider = { credentialStore.getToken(ServiceType.JIRA) }
        )
    }

    override suspend fun getTicketDetails(ticketId: String): TicketDetails? {
        val client = createClient() ?: return null
        return when (val result = client.getIssue(ticketId)) {
            is ApiResult.Success -> {
                val issue = result.data
                TicketDetails(
                    key = issue.key,
                    summary = issue.fields.summary,
                    description = issue.fields.description,
                    type = issue.fields.issuetype?.name
                )
            }
            is ApiResult.Error -> {
                log.warn("[Jira:TicketProvider] Failed to get ticket $ticketId: ${result.message}")
                null
            }
        }
    }

    override suspend fun getAvailableTransitions(ticketId: String): List<TicketTransition> {
        val client = createClient() ?: return emptyList()
        return when (val result = client.getTransitions(ticketId)) {
            is ApiResult.Success -> result.data.map { t ->
                TicketTransition(
                    id = t.id,
                    name = t.name,
                    targetStatus = t.to.name
                )
            }
            is ApiResult.Error -> {
                log.warn("[Jira:TicketProvider] Failed to get transitions for $ticketId: ${result.message}")
                emptyList()
            }
        }
    }

    override suspend fun transitionTicket(ticketId: String, transitionId: String): Boolean {
        val client = createClient() ?: return false
        return when (val result = client.transitionIssue(ticketId, transitionId)) {
            is ApiResult.Success -> true
            is ApiResult.Error -> {
                log.warn("[Jira:TicketProvider] Failed to transition $ticketId: ${result.message}")
                false
            }
        }
    }

    override fun showTransitionDialog(
        project: com.intellij.openapi.project.Project,
        ticketId: String,
        onTransitioned: () -> Unit
    ) {
        val client = createClient() ?: return
        val scope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
        )

        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val result = client.getTransitions(ticketId, expandFields = true)
            val transitions = when (result) {
                is ApiResult.Success -> result.data
                is ApiResult.Error -> {
                    log.warn("[Jira:TicketProvider] Failed to get transitions for $ticketId")
                    return@launch
                }
            }

            com.intellij.openapi.application.invokeLater {
                if (transitions.isEmpty()) {
                    log.warn("[Jira:TicketProvider] No transitions available for $ticketId")
                    return@invokeLater
                }

                if (transitions.size == 1 && transitions[0].fields.isNullOrEmpty()) {
                    // Single transition, no required fields — execute directly
                    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        transitionTicket(ticketId, transitions[0].id)
                        com.intellij.openapi.application.invokeLater { onTransitioned() }
                    }
                } else {
                    // Show popup to pick transition, then show dialog if fields required
                    val popup = javax.swing.JPopupMenu()
                    for (transition in transitions) {
                        val item = javax.swing.JMenuItem(transition.name)
                        item.addActionListener {
                            val hasRequiredFields = transition.fields?.any { it.value.required } == true
                            if (hasRequiredFields) {
                                com.workflow.orchestrator.jira.ui.TransitionDialog(
                                    project, ticketId, transition, scope, onTransitioned
                                ).show()
                            } else {
                                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                    transitionTicket(ticketId, transition.id)
                                    com.intellij.openapi.application.invokeLater { onTransitioned() }
                                }
                            }
                        }
                        popup.add(item)
                    }
                    // Show popup near the mouse
                    val mousePos = java.awt.MouseInfo.getPointerInfo().location
                    popup.show(null, mousePos.x, mousePos.y)
                }
            }
        }
    }
}

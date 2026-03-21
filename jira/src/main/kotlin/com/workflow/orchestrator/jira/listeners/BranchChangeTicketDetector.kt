package com.workflow.orchestrator.jira.listeners

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vcs.BranchChangeListener
import com.intellij.openapi.wm.WindowManager
import com.workflow.orchestrator.core.auth.CredentialStore
import com.workflow.orchestrator.core.events.EventBus
import com.workflow.orchestrator.core.events.WorkflowEvent
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ServiceType
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.jira.api.JiraApiClient
import com.workflow.orchestrator.jira.service.ActiveTicketService
import com.workflow.orchestrator.jira.ui.TicketDetectionPopup
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

        // Skip if the detected ticket is already the active ticket
        val currentActiveTicket = settings.state.activeTicketId
        if (currentActiveTicket == ticketId) {
            log.debug("[Jira:Branch] Ticket $ticketId is already active, skipping detection")
            return
        }

        // Fetch ticket details from Jira in background, then show confirmation popup
        scope.launch {
            val credentialStore = CredentialStore()
            val apiClient = JiraApiClient(
                baseUrl = jiraUrl.trimEnd('/'),
                tokenProvider = { credentialStore.getToken(ServiceType.JIRA) }
            )

            val result = apiClient.getIssue(ticketId)
            if (result is ApiResult.Success) {
                val issue = result.data
                val summary = issue.fields.summary
                val sprintName = issue.fields.sprint?.name
                val assigneeName = issue.fields.assignee?.displayName

                // Check if this branch was previously dismissed
                if (branchName in dismissedBranches) {
                    log.info("[Jira:Branch] Branch '$branchName' was previously dismissed, showing banner only")
                    // Emit event for Sprint tab banner
                    val eventBus = project.getService(EventBus::class.java)
                    eventBus.emit(
                        WorkflowEvent.TicketDetected(
                            ticketKey = ticketId,
                            ticketSummary = summary,
                            sprint = sprintName,
                            assignee = assigneeName,
                            branchName = branchName
                        )
                    )
                    return@launch
                }

                invokeLater {
                    val frame = WindowManager.getInstance().getFrame(project) ?: return@invokeLater

                    TicketDetectionPopup(
                        ticketKey = ticketId,
                        summary = summary,
                        sprint = sprintName,
                        assignee = assigneeName,
                        onAccept = {
                            settings.state.activeTicketId = ticketId
                            settings.state.activeTicketSummary = summary
                            ActiveTicketService.getInstance(project).setActiveTicket(ticketId, summary)
                            log.info("[Jira:Branch] User accepted ticket $ticketId as active")
                        },
                        onDismiss = {
                            dismissedBranches.add(branchName)
                            log.info("[Jira:Branch] User dismissed detection for branch '$branchName'")
                            // Emit event for Sprint tab banner
                            scope.launch {
                                val eventBus = project.getService(EventBus::class.java)
                                eventBus.emit(
                                    WorkflowEvent.TicketDetected(
                                        ticketKey = ticketId,
                                        ticketSummary = summary,
                                        sprint = sprintName,
                                        assignee = assigneeName,
                                        branchName = branchName
                                    )
                                )
                            }
                        }
                    ).show(frame)
                }
            } else {
                log.warn("[Jira:Branch] Failed to fetch issue $ticketId from Jira")
            }
        }
    }

    override fun dispose() {
        scope.cancel()
        log.info("[Jira:Branch] BranchChangeTicketDetector disposed")
    }

    companion object {
        /** Branches the user has dismissed detection for (resets on IDE restart). */
        val dismissedBranches: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf())
    }
}

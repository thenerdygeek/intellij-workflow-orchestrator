package com.workflow.orchestrator.jira.vcs

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.CheckinProjectPanel
import com.intellij.openapi.vcs.changes.CommitContext
import com.intellij.openapi.vcs.checkin.CheckinHandler
import com.intellij.openapi.vcs.checkin.CheckinHandlerFactory
import com.workflow.orchestrator.core.auth.CredentialStore
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ServiceType
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.jira.api.JiraApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * After a successful commit, suggests transitioning the Jira ticket
 * to "In Progress" if it's still in "To Do" or "Open" status.
 * Uses a non-blocking notification with an action button.
 */
class PostCommitTransitionHandlerFactory : CheckinHandlerFactory() {
    override fun createHandler(panel: CheckinProjectPanel, commitContext: CommitContext): CheckinHandler {
        return PostCommitTransitionHandler(panel.project)
    }
}

class PostCommitTransitionHandler(private val project: Project) : CheckinHandler() {

    private val log = Logger.getInstance(PostCommitTransitionHandler::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val credentialStore = CredentialStore()

    override fun checkinSuccessful() {
        val settings = PluginSettings.getInstance(project)
        val ticketId = settings.state.activeTicketId
        if (ticketId.isNullOrBlank()) return

        val baseUrl = settings.connections.jiraUrl.orEmpty().trimEnd('/')
        if (baseUrl.isBlank()) return

        scope.launch {
            try {
                val client = JiraApiClient(baseUrl) { credentialStore.getToken(ServiceType.JIRA) }

                when (val result = client.getIssue(ticketId)) {
                    is ApiResult.Success -> {
                        val currentStatus = result.data.fields.status.name
                        if (PostCommitTransitionLogic.shouldSuggestTransition(currentStatus)) {
                            when (val transitions = client.getTransitions(ticketId)) {
                                is ApiResult.Success -> {
                                    val inProgressTransition = transitions.data.find {
                                        it.to.name.equals("In Progress", ignoreCase = true)
                                    }
                                    if (inProgressTransition != null) {
                                        com.intellij.openapi.application.invokeLater {
                                            val notification = com.intellij.notification.NotificationGroupManager.getInstance()
                                                .getNotificationGroup("workflow.automation")
                                                .createNotification(
                                                    "Transition $ticketId?",
                                                    "$ticketId is still '$currentStatus'. Move to In Progress?",
                                                    com.intellij.notification.NotificationType.INFORMATION
                                                )
                                            notification.addAction(object : com.intellij.notification.NotificationAction("Transition") {
                                                override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent, notification: com.intellij.notification.Notification) {
                                                    notification.expire()
                                                    scope.launch {
                                                        client.transitionIssue(ticketId, inProgressTransition.id)
                                                        log.info("[Jira:PostCommit] Transitioned $ticketId to In Progress")
                                                    }
                                                }
                                            })
                                            notification.notify(project)
                                        }
                                    }
                                }
                                is ApiResult.Error -> log.debug("[Jira:PostCommit] Could not fetch transitions for $ticketId")
                            }
                        }
                    }
                    is ApiResult.Error -> log.debug("[Jira:PostCommit] Could not fetch $ticketId: ${result.message}")
                }
            } catch (e: Exception) {
                log.debug("[Jira:PostCommit] Error checking ticket status: ${e.message}")
            }
        }
    }
}

/** Pure logic — testable without IntelliJ dependencies. */
object PostCommitTransitionLogic {
    private val NEEDS_TRANSITION_STATUSES = setOf("to do", "open", "new", "backlog", "selected for development")

    fun shouldSuggestTransition(currentStatus: String): Boolean {
        if (currentStatus.isBlank()) return false
        return currentStatus.lowercase() in NEEDS_TRANSITION_STATUSES
    }
}

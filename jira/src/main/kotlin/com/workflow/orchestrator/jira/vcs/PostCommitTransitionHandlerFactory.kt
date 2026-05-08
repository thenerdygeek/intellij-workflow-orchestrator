package com.workflow.orchestrator.jira.vcs

import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.CheckinProjectPanel
import com.intellij.openapi.vcs.changes.CommitContext
import com.intellij.openapi.vcs.checkin.CheckinHandler
import com.intellij.openapi.vcs.checkin.CheckinHandlerFactory
import com.workflow.orchestrator.core.services.jira.TransitionDialogOpener
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.jira.service.JiraServiceImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * After a successful commit, suggests transitioning the Jira ticket
 * if it's still in a "not started" status.
 * Uses a non-blocking notification with an action button that opens
 * [TransitionDialogOpener] rather than performing a direct transition.
 */
class PostCommitTransitionHandlerFactory : CheckinHandlerFactory() {
    override fun createHandler(panel: CheckinProjectPanel, commitContext: CommitContext): CheckinHandler {
        return PostCommitTransitionHandler(panel.project)
    }
}

class PostCommitTransitionHandler(private val project: Project) : CheckinHandler() {

    private val log = Logger.getInstance(PostCommitTransitionHandler::class.java)

    override fun checkinSuccessful() {
        val settings = PluginSettings.getInstance(project)
        if (!settings.state.autoTransitionOnCommit) return

        val ticketId = settings.state.activeTicketId
        if (ticketId.isNullOrBlank()) return

        // Fire-and-forget: post-commit transition check must not block the commit flow.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            if (project.isDisposed) return@launch
            try {
                val jiraService = JiraServiceImpl.getInstance(project)
                val issueResult = jiraService.getTicket(ticketId)
                if (issueResult.isError) {
                    log.debug("[Jira:PostCommit] Could not fetch $ticketId: ${issueResult.summary}")
                } else {
                    val currentStatus = issueResult.data!!.status
                    if (PostCommitTransitionLogic.shouldSuggestTransition(currentStatus)) {
                        com.intellij.openapi.application.invokeLater {
                            val notification = com.intellij.notification.NotificationGroupManager.getInstance()
                                .getNotificationGroup("workflow.automation")
                                .createNotification(
                                    "Transition $ticketId?",
                                    "$ticketId is still '$currentStatus'. Open transition dialog?",
                                    com.intellij.notification.NotificationType.INFORMATION
                                )
                            notification.addAction(object : com.intellij.notification.NotificationAction("Open transition dialog\u2026") {
                                override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent, notification: com.intellij.notification.Notification) {
                                    notification.expire()
                                    if (!project.isDisposed) {
                                        project.service<TransitionDialogOpener>().open(project, ticketId, transitionId = null)
                                    }
                                }
                            })
                            notification.notify(project)
                        }
                    }
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

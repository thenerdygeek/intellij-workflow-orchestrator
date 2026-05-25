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
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
        // Scope is tied to the project Disposable so it is cancelled when the project
        // closes, preventing a leak for the process lifetime (audit finding jira:F-6).
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        Disposer.register(project as Disposable) { scope.cancel("project disposed") }
        scope.launch {
            if (project.isDisposed) return@launch
            try {
                val jiraService = JiraServiceImpl.getInstance(project)
                val issueResult = jiraService.getTicket(ticketId)
                if (issueResult.isError) {
                    log.debug("[Jira:PostCommit] Could not fetch $ticketId: ${issueResult.summary}")
                } else {
                    val currentStatus = issueResult.data!!.status
                    val triggerStatuses = PostCommitTransitionLogic.parseTriggerStatuses(
                        settings.state.postCommitTransitionTriggerStatuses
                    )
                    if (PostCommitTransitionLogic.shouldSuggestTransition(currentStatus, triggerStatuses)) {
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
    /**
     * Default "not started yet" status names. Mirrors the default of
     * [PluginSettings.postCommitTransitionTriggerStatuses] so the behavior is identical
     * out of the box when the setting is absent/blank (audit jira:F-14).
     */
    val DEFAULT_TRIGGER_STATUSES = setOf("to do", "open", "new", "backlog", "selected for development")

    /**
     * Parses the comma-separated [PluginSettings.postCommitTransitionTriggerStatuses] setting into
     * a lowercased set. Each entry is trimmed, lowercased, and blank entries are dropped. A null or
     * effectively-empty configuration falls back to [DEFAULT_TRIGGER_STATUSES].
     */
    fun parseTriggerStatuses(raw: String?): Set<String> {
        val parsed = raw
            ?.split(',')
            ?.map { it.trim().lowercase() }
            ?.filter { it.isNotBlank() }
            ?.toSet()
            .orEmpty()
        return parsed.ifEmpty { DEFAULT_TRIGGER_STATUSES }
    }

    fun shouldSuggestTransition(
        currentStatus: String,
        triggerStatuses: Set<String> = DEFAULT_TRIGGER_STATUSES
    ): Boolean {
        if (currentStatus.isBlank()) return false
        return currentStatus.lowercase() in triggerStatuses
    }
}

package com.workflow.orchestrator.jira.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.notifications.WorkflowNotificationService
import com.workflow.orchestrator.jira.service.JiraServiceImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import javax.swing.JButton
import javax.swing.JPanel

/**
 * Compact comment input bar pinned to the bottom of the ticket detail panel.
 *
 * Provides a text field and send button for quickly posting comments
 * to the currently displayed Jira issue without leaving the IDE.
 */
class QuickCommentPanel(private val project: Project) : JPanel(BorderLayout()), com.intellij.openapi.Disposable {

    private val log = Logger.getInstance(QuickCommentPanel::class.java)
    private val commentField = JBTextField()
    private val sendButton = JButton(AllIcons.Actions.Execute)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    var issueKey: String = ""
    var onCommentPosted: (() -> Unit)? = null

    init {
        commentField.emptyText.text = "Add a comment..."
        sendButton.toolTipText = "Post comment"
        sendButton.addActionListener { postComment() }
        commentField.addActionListener { postComment() } // Enter key posts

        add(commentField, BorderLayout.CENTER)
        add(sendButton, BorderLayout.EAST)
        border = JBUI.Borders.empty(4, 8)
    }

    private fun postComment() {
        val text = commentField.text.trim()
        if (text.isEmpty() || issueKey.isEmpty()) return

        sendButton.isEnabled = false
        commentField.isEnabled = false

        scope.launch {
            val jiraServiceImpl = JiraServiceImpl.getInstance(project)
            val apiClient = jiraServiceImpl.getApiClient()

            if (apiClient == null) {
                withContext(Dispatchers.EDT) {
                    sendButton.isEnabled = true
                    commentField.isEnabled = true
                    WorkflowNotificationService.getInstance(project).notifyWarning(
                        WorkflowNotificationService.GROUP_JIRA,
                        "Jira",
                        "Jira not configured. Cannot post comment."
                    )
                }
                return@launch
            }

            val result = apiClient.addComment(issueKey, text)

            withContext(Dispatchers.EDT) {
                when (result) {
                    is ApiResult.Success -> {
                        log.info("[Jira:UI] Comment posted to $issueKey")
                        commentField.text = ""
                        onCommentPosted?.invoke()
                    }
                    is ApiResult.Error -> {
                        log.warn("[Jira:UI] Failed to post comment to $issueKey: ${result.message}")
                        WorkflowNotificationService.getInstance(project).notifyError(
                            WorkflowNotificationService.GROUP_JIRA,
                            "Comment Failed",
                            "Failed to post comment: ${result.message}"
                        )
                    }
                }
                sendButton.isEnabled = true
                commentField.isEnabled = true
            }
        }
    }

    override fun dispose() {
        scope.cancel()
    }
}

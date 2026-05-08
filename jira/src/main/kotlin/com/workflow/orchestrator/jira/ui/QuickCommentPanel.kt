package com.workflow.orchestrator.jira.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.core.model.jira.CommentVisibility
import com.workflow.orchestrator.core.model.jira.GroupOption
import com.workflow.orchestrator.core.model.jira.RoleOption
import com.workflow.orchestrator.core.model.jira.VisibilityType
import com.workflow.orchestrator.core.notifications.WorkflowNotificationService
import com.workflow.orchestrator.core.services.JiraService
import com.workflow.orchestrator.core.ui.ComboBoxWidth
import com.workflow.orchestrator.core.ui.bindBoundedWidth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JPanel

/**
 * Compact comment input bar pinned to the bottom of the ticket detail panel.
 *
 * Provides a text field, visibility dropdown ("Public" + role/group options),
 * and send button for quickly posting comments to the currently displayed Jira
 * issue without leaving the IDE. Visibility options are loaded async on first
 * panel show; the dropdown defaults to "Public" (no restriction).
 */
class QuickCommentPanel(private val project: Project) : JPanel(BorderLayout()), com.intellij.openapi.Disposable {

    private val log = Logger.getInstance(QuickCommentPanel::class.java)
    private val commentField = JBTextField()
    private val sendButton = JButton(AllIcons.Actions.Execute)

    /**
     * Wrapper for combo entries — `null` payload is the "Public" item; non-null carries
     * the actual visibility selection.
     */
    private data class VisibilityEntry(val label: String, val visibility: CommentVisibility?) {
        override fun toString(): String = label
    }

    private val visibilityModel = DefaultComboBoxModel<VisibilityEntry>().apply {
        addElement(VisibilityEntry("Public", null))
    }
    private val visibilityCombo = JComboBox(visibilityModel).apply {
        toolTipText = "Restrict comment to a project role or group"
        bindBoundedWidth(ComboBoxWidth.DEFAULT)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    var issueKey: String = ""
        set(value) {
            val changed = field != value
            field = value
            if (changed) maybeLoadVisibilityOptions(value)
        }

    var onCommentPosted: (() -> Unit)? = null
    private val defaultPlaceholder = "Add a comment..."

    /** Tracks the project key we've already loaded options for, to avoid re-fetching. */
    @Volatile private var loadedForProjectKey: String? = null

    init {
        commentField.emptyText.text = defaultPlaceholder
        sendButton.toolTipText = "Post comment"
        sendButton.addActionListener { postComment() }
        commentField.addActionListener { postComment() } // Enter key posts

        val rightPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
            add(visibilityCombo)
            add(sendButton)
        }

        add(commentField, BorderLayout.CENTER)
        add(rightPanel, BorderLayout.EAST)
        border = JBUI.Borders.empty(4, 8)
    }

    /**
     * Enable or disable the comment input + send button. When disabling, an
     * optional [placeholder] is shown in the field's empty-text slot to explain
     * why (e.g. "You don't have permission to comment"). On re-enable, the
     * default placeholder is restored.
     */
    fun setInputEnabled(enabled: Boolean, placeholder: String? = null) {
        commentField.isEnabled = enabled
        sendButton.isEnabled = enabled
        visibilityCombo.isEnabled = enabled
        commentField.emptyText.text = if (!enabled && !placeholder.isNullOrBlank()) {
            placeholder
        } else {
            defaultPlaceholder
        }
    }

    private fun extractProjectKey(issueKey: String): String? {
        val dash = issueKey.indexOf('-')
        return if (dash > 0) issueKey.substring(0, dash) else null
    }

    /**
     * Lazily fetches role + group options the first time we see a new project key.
     * Network call is async — never blocks panel render. On failure the dropdown
     * silently stays at "Public", which is the safe default.
     */
    private fun maybeLoadVisibilityOptions(issueKey: String) {
        val projectKey = extractProjectKey(issueKey) ?: return
        if (loadedForProjectKey == projectKey) return
        loadedForProjectKey = projectKey

        scope.launch {
            val jiraService = project.getService(JiraService::class.java) ?: return@launch
            val result = jiraService.getCommentVisibilityOptions(projectKey)
            if (result.isError) {
                log.info("[Jira:UI] Visibility options unavailable for $projectKey: ${result.summary}")
                return@launch
            }
            withContext(Dispatchers.EDT) {
                rebuildVisibilityModel(result.data.roles, result.data.groups)
            }
        }
    }

    private fun rebuildVisibilityModel(roles: List<RoleOption>, groups: List<GroupOption>) {
        visibilityModel.removeAllElements()
        visibilityModel.addElement(VisibilityEntry("Public", null))
        roles.forEach { r ->
            visibilityModel.addElement(
                VisibilityEntry("Role: ${r.name}", CommentVisibility(VisibilityType.ROLE, r.name))
            )
        }
        groups.forEach { g ->
            visibilityModel.addElement(
                VisibilityEntry("Group: ${g.name}", CommentVisibility(VisibilityType.GROUP, g.name))
            )
        }
    }

    private fun postComment() {
        val text = commentField.text.trim()
        if (text.isEmpty() || issueKey.isEmpty()) return

        val visibility = (visibilityCombo.selectedItem as? VisibilityEntry)?.visibility

        sendButton.isEnabled = false
        commentField.isEnabled = false
        visibilityCombo.isEnabled = false

        scope.launch {
            val jiraService = project.getService(JiraService::class.java)
            if (jiraService == null) {
                withContext(Dispatchers.EDT) {
                    sendButton.isEnabled = true
                    commentField.isEnabled = true
                    visibilityCombo.isEnabled = true
                    WorkflowNotificationService.getInstance(project).notifyWarning(
                        WorkflowNotificationService.GROUP_JIRA,
                        "Jira",
                        "Jira not configured. Cannot post comment."
                    )
                }
                return@launch
            }

            val result = jiraService.addComment(issueKey, text, visibility)

            withContext(Dispatchers.EDT) {
                if (result.isError) {
                    log.warn("[Jira:UI] Failed to post comment to $issueKey: ${result.summary}")
                    WorkflowNotificationService.getInstance(project).notifyError(
                        WorkflowNotificationService.GROUP_JIRA,
                        "Comment Failed",
                        "Failed to post comment: ${result.summary}"
                    )
                } else {
                    log.info("[Jira:UI] Comment posted to $issueKey")
                    commentField.text = ""
                    onCommentPosted?.invoke()
                }
                sendButton.isEnabled = true
                commentField.isEnabled = true
                visibilityCombo.isEnabled = true
            }
        }
    }

    override fun dispose() {
        scope.cancel()
    }
}

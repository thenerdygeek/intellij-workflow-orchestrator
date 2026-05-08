package com.workflow.orchestrator.handover.ui.panels

import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.core.events.EventBus
import com.workflow.orchestrator.core.events.WorkflowEvent
import com.workflow.orchestrator.core.model.jira.CommentVisibility
import com.workflow.orchestrator.core.model.jira.GroupOption
import com.workflow.orchestrator.core.model.jira.RoleOption
import com.workflow.orchestrator.core.model.jira.VisibilityType
import com.workflow.orchestrator.core.services.JiraService
import com.workflow.orchestrator.core.ui.ComboBoxWidth
import com.workflow.orchestrator.core.ui.StatusColors
import com.workflow.orchestrator.core.ui.bindBoundedWidth
import com.workflow.orchestrator.handover.service.HandoverStateService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.FlowLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JPanel
import javax.swing.SwingConstants

class JiraCommentPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val log = Logger.getInstance(JiraCommentPanel::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // -- Current state (kept in sync via updateFromState) --
    private var activeTicketId: String = ""

    /** Project key the visibility dropdown was last loaded for (so we don't re-fetch). */
    @Volatile private var loadedForProjectKey: String? = null

    val commentPreview = JBTextArea(12, 40).apply {
        isEditable = false
        font = JBUI.Fonts.create(EditorColorsManager.getInstance().globalScheme.editorFontName, 12)
    }
    val editButton = JButton("Edit")
    val postButton = JButton("Post Comment").apply {
        isEnabled = false
        toolTipText = "Select a Jira ticket in the Sprint tab first"
    }
    val statusLabel = JBLabel("")

    /**
     * Wrapper for combo entries — `null` payload is the "Public" item.
     */
    private data class VisibilityEntry(val label: String, val visibility: CommentVisibility?) {
        override fun toString(): String = label
    }

    private val visibilityModel = DefaultComboBoxModel<VisibilityEntry>().apply {
        addElement(VisibilityEntry("Public", null))
    }
    private val visibilityCombo = JComboBox(visibilityModel).apply {
        toolTipText = "Restrict closure comment to a project role or group"
        bindBoundedWidth(ComboBoxWidth.DEFAULT)
    }

    // Empty-state card shown when no ticket is active
    private val cardLayout = CardLayout()
    private val cardPanel = JPanel(cardLayout)
    private val emptyLabel = JBLabel("Pick a ticket from the Sprint tab to draft a closure comment.").apply {
        foreground = StatusColors.SECONDARY_TEXT
        horizontalAlignment = SwingConstants.CENTER
        border = JBUI.Borders.emptyTop(40)
    }

    init {
        border = JBUI.Borders.empty(8)

        editButton.addActionListener {
            commentPreview.isEditable = !commentPreview.isEditable
            editButton.text = if (commentPreview.isEditable) "Done" else "Edit"
        }

        postButton.addActionListener {
            onPostClicked()
        }

        val buttonPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            add(editButton)
            add(postButton)
            add(JBLabel("Visibility:"))
            add(visibilityCombo)
        }

        val southPanel = JPanel(BorderLayout()).apply {
            add(buttonPanel, BorderLayout.WEST)
            add(statusLabel, BorderLayout.EAST)
        }

        val commentView = JPanel(BorderLayout()).apply {
            add(JBScrollPane(commentPreview), BorderLayout.CENTER)
        }

        cardPanel.add(commentView, "comment")
        cardPanel.add(emptyLabel, "empty")
        cardLayout.show(cardPanel, "empty")

        add(handoverPanelHeader("JIRA CLOSURE COMMENT"), BorderLayout.NORTH)
        add(cardPanel, BorderLayout.CENTER)
        add(southPanel, BorderLayout.SOUTH)
    }

    /**
     * Called from [HandoverPanel]'s stateFlow collector (on EDT via Dispatchers.EDT scope).
     * Updates comment text and button state atomically.
     */
    fun updateFromState(ticketId: String, commentText: String) {
        activeTicketId = ticketId
        if (ticketId.isBlank()) {
            cardLayout.show(cardPanel, "empty")
            postButton.isEnabled = false
            postButton.toolTipText = "Select a Jira ticket in the Sprint tab first"
            statusLabel.text = ""
        } else {
            commentPreview.text = commentText
            cardLayout.show(cardPanel, "comment")
            refreshPostButtonState()
            maybeLoadVisibilityOptions(ticketId)
        }
    }

    /** Back-compat: if callers set text independently, respect it and re-check button. */
    fun setCommentText(text: String) {
        commentPreview.text = text
        refreshPostButtonState()
    }

    // -----------------------------------------------------------------------
    // Private
    // -----------------------------------------------------------------------

    private fun extractProjectKey(issueKey: String): String? {
        val dash = issueKey.indexOf('-')
        return if (dash > 0) issueKey.substring(0, dash) else null
    }

    /**
     * Lazily fetches role + group options the first time we see a new project key.
     * Network call is async — never blocks panel render. On failure the dropdown
     * silently stays at "Public", which is the safe default.
     */
    private fun maybeLoadVisibilityOptions(ticketId: String) {
        val projectKey = extractProjectKey(ticketId) ?: return
        if (loadedForProjectKey == projectKey) return
        loadedForProjectKey = projectKey

        scope.launch {
            val jiraService = project.getService(JiraService::class.java) ?: return@launch
            val result = jiraService.getCommentVisibilityOptions(projectKey)
            if (result.isError) {
                log.info("[Handover:JiraComment] Visibility options unavailable for $projectKey: ${result.summary}")
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

    private fun refreshPostButtonState() {
        val canPost = activeTicketId.isNotBlank() && commentPreview.text.isNotBlank()
        postButton.isEnabled = canPost
        postButton.toolTipText = when {
            activeTicketId.isBlank() -> "Select a Jira ticket in the Sprint tab first"
            commentPreview.text.isBlank() -> "No automation results yet — run a suite first"
            else -> "Post closure comment to $activeTicketId"
        }
    }

    private fun onPostClicked() {
        val ticketKey = activeTicketId
        val body = commentPreview.text
        if (ticketKey.isBlank() || body.isBlank()) return

        val visibility = (visibilityCombo.selectedItem as? VisibilityEntry)?.visibility

        postButton.isEnabled = false
        editButton.isEnabled = false
        visibilityCombo.isEnabled = false
        statusLabel.text = "Posting..."
        log.info(
            "[Handover:JiraComment] Posting comment to $ticketKey " +
                "(visibility=${visibility?.let { "${it.type.name.lowercase()}:${it.value}" } ?: "public"})"
        )

        scope.launch {
            val jiraService = project.getService(JiraService::class.java)
            if (jiraService == null) {
                withContext(Dispatchers.EDT) {
                    statusLabel.text = "Jira service unavailable"
                    postButton.isEnabled = true
                    editButton.isEnabled = true
                    visibilityCombo.isEnabled = true
                }
                return@launch
            }

            val result = jiraService.addComment(ticketKey, body, visibility)

            withContext(Dispatchers.EDT) {
                editButton.isEnabled = true
                visibilityCombo.isEnabled = true
                if (result.isError) {
                    log.warn("[Handover:JiraComment] Failed to post comment: ${result.summary}")
                    statusLabel.text = result.summary.take(80)
                    postButton.isEnabled = true
                } else {
                    log.info("[Handover:JiraComment] Comment posted successfully to $ticketKey")
                    statusLabel.text = "Posted"
                    postButton.isEnabled = false

                    // Flip the checklist dot and emit the event so other subscribers see it too
                    HandoverStateService.getInstance(project).markJiraCommentPosted()
                    project.getService(EventBus::class.java)
                        ?.emit(WorkflowEvent.JiraCommentPosted(ticketId = ticketKey, commentId = ""))
                }
            }
        }
    }
}

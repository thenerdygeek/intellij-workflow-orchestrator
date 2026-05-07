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
import com.workflow.orchestrator.core.services.JiraService
import com.workflow.orchestrator.core.ui.StatusColors
import com.workflow.orchestrator.handover.service.HandoverStateService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.CardLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.SwingConstants

class JiraCommentPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val log = Logger.getInstance(JiraCommentPanel::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // -- Current state (kept in sync via updateFromState) --
    private var activeTicketId: String = ""

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

        val buttonPanel = JPanel().apply {
            add(editButton)
            add(postButton)
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

        postButton.isEnabled = false
        editButton.isEnabled = false
        statusLabel.text = "Posting..."
        log.info("[Handover:JiraComment] Posting comment to $ticketKey")

        scope.launch {
            val jiraService = project.getService(JiraService::class.java)
            if (jiraService == null) {
                withContext(Dispatchers.EDT) {
                    statusLabel.text = "Jira service unavailable"
                    postButton.isEnabled = true
                    editButton.isEnabled = true
                }
                return@launch
            }

            val result = jiraService.addComment(ticketKey, body)

            withContext(Dispatchers.EDT) {
                editButton.isEnabled = true
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

package com.workflow.orchestrator.pullrequest.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.core.model.PrComment
import com.workflow.orchestrator.core.model.PrCommentState
import com.workflow.orchestrator.core.services.BitbucketService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.DefaultListModel
import javax.swing.JButton

/**
 * Comments sub-tab panel.
 * Lists PR comments, allows posting general comments, replying, and toggling resolved state.
 *
 * SmartPoller is wired separately in Task 5.
 * Lifecycle: call [close] (or dispose the parent window) to cancel the coroutine scope.
 */
class CommentsTabPanel(
    private val project: Project,
    private val service: BitbucketService,
    private val projectKey: String,
    private val repoSlug: String,
    private val prId: Int,
) : JBPanel<CommentsTabPanel>(BorderLayout()), AutoCloseable {

    val vm = CommentsViewModel(service, projectKey, repoSlug, prId)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val listModel = DefaultListModel<PrComment>()
    private val commentList = JBList(listModel).apply {
        cellRenderer = CommentRowRenderer()
        fixedCellHeight = -1
        visibleRowCount = 10
    }

    private val statusLabel = JBLabel("Loading…").apply {
        border = JBUI.Borders.empty(0, 8)
    }

    private val postTextArea = JBTextArea(4, 40).apply {
        lineWrap = true
        wrapStyleWord = true
        border = JBUI.Borders.empty(4)
        emptyText.text = "Add a comment…"
    }

    init {
        border = JBUI.Borders.empty()

        // Top toolbar: Refresh + status
        val toolbar = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 4, 4)).apply {
            add(JButton("Refresh").apply { addActionListener { triggerRefresh() } })
            add(statusLabel)
        }
        add(toolbar, BorderLayout.NORTH)

        // Center: comment list + per-selection action buttons
        val centerPanel = JBPanel<JBPanel<*>>(BorderLayout())
        centerPanel.add(JBScrollPane(commentList), BorderLayout.CENTER)

        val selectionActionsPanel = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 4, 4)).apply {
            add(JButton("Reply").apply { addActionListener { replySelected() } })
            add(JButton("Toggle Resolved").apply { addActionListener { toggleResolvedSelected() } })
        }
        centerPanel.add(selectionActionsPanel, BorderLayout.SOUTH)
        add(centerPanel, BorderLayout.CENTER)

        // Bottom: post-general-comment form
        val postPanel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = JBUI.Borders.empty(8, 12)
            add(JBLabel("Add general comment:"), BorderLayout.NORTH)
            add(JBScrollPane(postTextArea), BorderLayout.CENTER)
            add(JButton("Post").apply { addActionListener { postGeneral() } }, BorderLayout.SOUTH)
        }
        add(postPanel, BorderLayout.SOUTH)

        preferredSize = Dimension(800, 600)

        vm.addChangeListener {
            ApplicationManager.getApplication().invokeLater {
                syncListModel()
                statusLabel.text = vm.lastError ?: "${listModel.size()} comment(s)"
            }
        }

        triggerRefresh()
    }

    /** Launches an IO-bound refresh via the ViewModel. */
    fun triggerRefresh() {
        statusLabel.text = "Loading…"
        scope.launch { vm.refresh() }
    }

    private fun syncListModel() {
        listModel.clear()
        vm.comments.forEach { listModel.addElement(it) }
    }

    private fun replySelected() {
        val sel = commentList.selectedValue ?: return
        val text = askForText("Reply to ${sel.author.displayName}") ?: return
        scope.launch { vm.reply(sel.id.toLong(), text) }
    }

    private fun toggleResolvedSelected() {
        val sel = commentList.selectedValue ?: return
        scope.launch {
            if (sel.state == PrCommentState.RESOLVED) vm.reopen(sel.id.toLong())
            else vm.resolve(sel.id.toLong())
        }
    }

    private fun postGeneral() {
        val text = postTextArea.text.trim()
        if (text.isBlank()) return
        scope.launch {
            if (vm.postGeneralComment(text)) {
                ApplicationManager.getApplication().invokeLater {
                    postTextArea.text = ""
                }
            }
        }
    }

    private fun askForText(title: String): String? {
        val input = Messages.showInputDialog(
            project,
            "Enter text:",
            title,
            null,
        )
        return input?.takeIf { it.isNotBlank() }
    }

    override fun close() {
        scope.cancel()
    }
}

package com.workflow.orchestrator.pullrequest.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.workflow.orchestrator.core.events.EventBus
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.core.model.PrComment
import com.workflow.orchestrator.core.model.PrCommentState
import com.workflow.orchestrator.core.polling.SmartPoller
import com.workflow.orchestrator.core.services.BitbucketService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
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
) : JBPanel<CommentsTabPanel>(BorderLayout()), AutoCloseable, Disposable {

    val vm = CommentsViewModel(
        service = service,
        projectKey = projectKey,
        repoSlug = repoSlug,
        prId = prId,
        eventBus = project.getService(EventBus::class.java),
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Held so [close] can call [removeComponentListener] and break the retain-cycle. */
    private lateinit var componentListener: java.awt.event.ComponentListener

    /** Auto-refresh poller: 30s base, 1.5× backoff, 5m cap. Starts/stops on tab visibility. */
    private val poller = SmartPoller(
        name = "PR#$prId-comments",
        baseIntervalMs = 30_000L,
        maxIntervalMs = 300_000L,
        scope = scope,
        action = {
            val sizeBefore = vm.comments.size
            vm.refresh()
            vm.comments.size != sizeBefore
        },
    )

    private val listModel = DefaultListModel<PrComment>()
    private val commentList = JBList(listModel).apply {
        cellRenderer = CommentRowRenderer()
        fixedCellHeight = -1
        visibleRowCount = 10
    }

    /**
     * "Toggle Resolved" button reference kept so its enabled state can be updated on
     * selection change. Disabled when no comment is selected or when the selected comment's
     * [PrCommentPermittedOps.transitionable] is false (server-side authorship guard).
     * Closes audit finding pullrequest:F-7.
     */
    private val toggleResolvedButton = JButton("Toggle Resolved").apply {
        isEnabled = false
        addActionListener { toggleResolvedSelected() }
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
            add(toggleResolvedButton)
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

        // Update "Toggle Resolved" button state whenever the selection changes.
        // Gate is permittedOperations.transitionable from the Bitbucket response —
        // the server populates this only for comments the current user may transition.
        commentList.addListSelectionListener {
            updateToggleResolvedState()
        }

        vm.addChangeListener {
            ApplicationManager.getApplication().invokeLater {
                syncListModel()
                statusLabel.text = vm.lastError ?: "${listModel.size()} comment(s)"
            }
        }

        // Start poller when this tab becomes visible; stop when hidden.
        // The listener reference is stored so close() can remove it and break the
        // retain-cycle: when PrDetailPanel.rebuildCommentsTab() replaces this panel,
        // the old panel stays in the Swing hierarchy as a non-active card and receives
        // spurious show/hide events, which could restart a cancelled poller. Keeping a
        // named handle and calling removeComponentListener in close() prevents both the
        // memory leak and the spurious restart. Closes audit finding pullrequest:F-8.
        componentListener = object : ComponentAdapter() {
            override fun componentShown(e: ComponentEvent) {
                poller.start()
                poller.setVisible(true)
            }

            override fun componentHidden(e: ComponentEvent) {
                poller.setVisible(false)
                poller.stop()
            }
        }
        addComponentListener(componentListener)

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
        updateToggleResolvedState()
    }

    /**
     * Enables "Toggle Resolved" only when a comment is selected AND its
     * [PrCommentPermittedOps.transitionable] is true.  The server populates
     * [PrCommentPermittedOps] based on ownership and comment state, so this
     * is a defence-in-depth client guard — the server still enforces the rule.
     * Closes audit finding pullrequest:F-7.
     */
    private fun updateToggleResolvedState() {
        val sel = commentList.selectedValue
        toggleResolvedButton.isEnabled = sel?.permittedOperations?.transitionable == true
    }

    private fun replySelected() {
        val sel = commentList.selectedValue ?: return
        val text = askForText("Reply to ${sel.author.displayName}") ?: return
        scope.launch { vm.reply(sel.id.toLong(), text) }
    }

    private fun toggleResolvedSelected() {
        val sel = commentList.selectedValue ?: return
        // Double-check even though the button should already be disabled for non-transitionable
        // comments (defence-in-depth against programmatic invocation paths).
        if (sel.permittedOperations?.transitionable != true) return
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
        // Remove the ComponentListener before cancelling the scope so that no
        // in-flight Swing visibility events can restart the already-cancelled poller.
        removeComponentListener(componentListener)
        poller.stop()
        scope.cancel()
    }

    /** Delegates to [close] so the panel can be registered with [com.intellij.openapi.util.Disposer]. */
    override fun dispose() = close()
}

package com.workflow.orchestrator.pullrequest.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.JBColor
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.core.bitbucket.BitbucketPrDetail
import com.workflow.orchestrator.pullrequest.service.PrListService
import kotlinx.coroutines.*
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JPanel
import javax.swing.JToggleButton
import javax.swing.ButtonGroup

/**
 * Main PR Dashboard panel — the top-level component placed in the PR tab.
 *
 * Layout:
 * - NORTH: toolbar (Create PR, Refresh) + filter toggle ("My PRs" | "Reviewing")
 * - CENTER: JBSplitter with PrListPanel (left) and PrDetailPanel (right)
 * - SOUTH: status bar
 */
class PrDashboardPanel(
    private val project: Project
) : JPanel(BorderLayout()), Disposable {

    private val log = Logger.getInstance(PrDashboardPanel::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // -- Sub-panels --
    private val listPanel = PrListPanel()
    private val detailPanel = PrDetailPanel(project)

    // -- Filter toggle --
    private val myPrsToggle = JToggleButton("My PRs").apply { isSelected = true }
    private val reviewingToggle = JToggleButton("Reviewing")
    private val filterGroup = ButtonGroup()

    // -- Status bar --
    private val statusLabel = JBLabel("Ready").apply {
        foreground = SECONDARY_TEXT
        font = font.deriveFont(JBUI.scale(11).toFloat())
        border = JBUI.Borders.empty(4, 8)
    }
    private val loadingIcon = JBLabel(AnimatedIcon.Default()).apply {
        border = JBUI.Borders.emptyRight(4)
        isVisible = false
    }

    // -- State --
    private var currentMyPrs: List<BitbucketPrDetail> = emptyList()
    private var currentReviewingPrs: List<BitbucketPrDetail> = emptyList()
    private var showReviewing = false

    init {
        background = JBColor.PanelBackground
        isOpaque = true

        setupLayout()
        setupListeners()
        startDataCollection()
    }

    // ---------------------------------------------------------------
    // Layout
    // ---------------------------------------------------------------

    private fun setupLayout() {
        // -- Top: toolbar + filter --
        val topPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(4, 8, 0, 8)
        }

        // Toolbar
        val actionGroup = DefaultActionGroup().apply {
            add(CreatePrAction())
            add(RefreshAction())
        }
        val toolbar = ActionManager.getInstance()
            .createActionToolbar("PrDashboard", actionGroup, true)
        toolbar.targetComponent = this
        topPanel.add(toolbar.component, BorderLayout.WEST)

        // Filter toggles
        val filterPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            border = JBUI.Borders.emptyLeft(8)
        }
        filterGroup.add(myPrsToggle)
        filterGroup.add(reviewingToggle)
        filterPanel.add(myPrsToggle)
        filterPanel.add(reviewingToggle)
        topPanel.add(filterPanel, BorderLayout.CENTER)

        add(topPanel, BorderLayout.NORTH)

        // -- Center: splitter --
        val splitter = JBSplitter(false, 0.30f).apply {
            setSplitterProportionKey("workflow.pr.splitter")
            firstComponent = listPanel
            secondComponent = detailPanel
            isOpaque = false
        }
        add(splitter, BorderLayout.CENTER)

        // -- Bottom: status bar --
        val bottomPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            border = JBUI.Borders.customLine(BORDER_COLOR, 1, 0, 0, 0)
        }
        bottomPanel.add(loadingIcon)
        bottomPanel.add(statusLabel)
        add(bottomPanel, BorderLayout.SOUTH)
    }

    // ---------------------------------------------------------------
    // Listeners
    // ---------------------------------------------------------------

    private fun setupListeners() {
        listPanel.onPrSelected = { prId ->
            detailPanel.showPr(prId)
        }

        myPrsToggle.addActionListener {
            showReviewing = false
            refreshListView()
        }
        reviewingToggle.addActionListener {
            showReviewing = true
            refreshListView()
        }
    }

    // ---------------------------------------------------------------
    // Data collection
    // ---------------------------------------------------------------

    private fun startDataCollection() {
        val prListService = PrListService.getInstance(project)
        prListService.startPolling()

        // Collect myPrs flow
        scope.launch {
            prListService.myPrs.collect { prs ->
                currentMyPrs = prs
                invokeLater {
                    refreshListView()
                    setLoading(false, "${prs.size} PRs loaded")
                }
            }
        }

        // Collect reviewingPrs flow
        scope.launch {
            prListService.reviewingPrs.collect { prs ->
                currentReviewingPrs = prs
                invokeLater { refreshListView() }
            }
        }

        // Initial load
        setLoading(true, "Loading pull requests...")
        scope.launch {
            prListService.refresh()
        }
    }

    private fun refreshListView() {
        val myItems = currentMyPrs.map { it.toPrListItem() }
        val reviewingItems = currentReviewingPrs.map { it.toPrListItem() }

        if (showReviewing) {
            listPanel.updatePrs(emptyList(), reviewingItems)
        } else {
            listPanel.updatePrs(myItems, reviewingItems)
        }
    }

    private fun setLoading(loading: Boolean, message: String) {
        loadingIcon.isVisible = loading
        statusLabel.text = message
    }

    // ---------------------------------------------------------------
    // DTO conversion
    // ---------------------------------------------------------------

    private fun BitbucketPrDetail.toPrListItem(): PrListItem {
        return PrListItem(
            id = id,
            title = title,
            authorName = author?.user?.displayName ?: "",
            status = state,
            reviewerCount = reviewers.size,
            updatedDate = updatedDate,
            fromBranch = fromRef?.displayId ?: "",
            toBranch = toRef?.displayId ?: ""
        )
    }

    // ---------------------------------------------------------------
    // Actions
    // ---------------------------------------------------------------

    private inner class RefreshAction : AnAction(
        "Refresh PRs",
        "Reload pull requests from Bitbucket",
        AllIcons.Actions.Refresh
    ) {
        override fun actionPerformed(e: AnActionEvent) {
            setLoading(true, "Refreshing...")
            scope.launch {
                PrListService.getInstance(project).refresh()
                invokeLater {
                    setLoading(false, "Refreshed")
                }
            }
        }
    }

    private inner class CreatePrAction : AnAction(
        "Create PR",
        "Create a new pull request (use the Build tab for full PR creation flow)",
        AllIcons.General.Add
    ) {
        override fun actionPerformed(e: AnActionEvent) {
            com.intellij.notification.NotificationGroupManager.getInstance()
                .getNotificationGroup("workflow.build")
                .createNotification(
                    "Create PR from the Handover tab for the full workflow, or use Bitbucket directly.",
                    com.intellij.notification.NotificationType.INFORMATION
                )
                .notify(project)
        }
    }

    // ---------------------------------------------------------------
    // Dispose
    // ---------------------------------------------------------------

    override fun dispose() {
        detailPanel.dispose()
        scope.cancel()
    }

    companion object {
        private val SECONDARY_TEXT = JBColor(0x656D76, 0x8B949E)
        private val BORDER_COLOR = JBColor(0xD1D9E0, 0x444D56)
    }
}

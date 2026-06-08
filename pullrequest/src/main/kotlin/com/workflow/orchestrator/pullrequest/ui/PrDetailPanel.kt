package com.workflow.orchestrator.pullrequest.ui

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.workflow.orchestrator.core.ui.StatusColors
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.core.bitbucket.BitbucketBuildStatus
import com.workflow.orchestrator.core.bitbucket.BitbucketCommit
import com.workflow.orchestrator.core.bitbucket.BitbucketMergeStatus
import com.workflow.orchestrator.core.bitbucket.BitbucketMergeStrategy
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.bitbucket.PrState
import com.workflow.orchestrator.core.bitbucket.BitbucketPrActivity
import com.workflow.orchestrator.core.bitbucket.BitbucketPrChange
import com.workflow.orchestrator.core.bitbucket.BitbucketPrDetail
import com.workflow.orchestrator.core.bitbucket.BitbucketPrRef
import com.workflow.orchestrator.core.ui.TimeFormatter
import com.workflow.orchestrator.core.settings.ConnectionSettings
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.bitbucket.BitbucketUser
import com.workflow.orchestrator.core.services.BitbucketService
import com.workflow.orchestrator.core.model.workflow.InteractionMode
import com.workflow.orchestrator.core.notifications.WorkflowNotificationService
import com.workflow.orchestrator.core.workflow.WorkflowContextService
import com.workflow.orchestrator.core.workflow.ui.ReadOnlyBanner
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindowManager
import com.workflow.orchestrator.core.ai.AgentChatRedirect
import com.workflow.orchestrator.core.util.HtmlEscape
import com.workflow.orchestrator.pullrequest.service.BitbucketBranchClientCache
import com.workflow.orchestrator.pullrequest.service.MarkdownToHtml
import com.workflow.orchestrator.pullrequest.service.PrActionService
import com.workflow.orchestrator.pullrequest.service.PrDetailService
import com.workflow.orchestrator.pullrequest.service.PrReviewSessionRegistry
import com.workflow.orchestrator.pullrequest.service.PrReviewTaskBuilder
import java.util.UUID
import com.workflow.orchestrator.core.util.StringUtils
import kotlinx.coroutines.*
import java.awt.*
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.event.HyperlinkEvent

/**
 * Right panel in the PR dashboard showing detailed PR information.
 * Uses CardLayout to switch between: empty state, loading, and detail view.
 */
class PrDetailPanel(
    private val project: Project
) : JPanel(CardLayout()), Disposable {

    private val log = Logger.getInstance(PrDetailPanel::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Phase 5 T15: amber banner shown when interactionMode == ReadOnly (spec §7.1).
    // Wired into the detail card's content layout — see [buildDetailPanel].
    private val workflowContextService = WorkflowContextService.getInstance(project)
    private val readOnlyBanner = ReadOnlyBanner(project).also {
        Disposer.register(this, it)
    }
    /** Shared client cache — reuses HTTP connections across all IO calls in this panel (ARC-2 fix). */
    private val clientCache = BitbucketBranchClientCache()

    /**
     * @Volatile mirrors the pattern on [cachedUsername]: the EDT writes these fields
     * via showPr/showPrDetail/refreshCurrentPr while Dispatchers.IO coroutines read
     * them (renderPrHeader approve-button toggle at line ~1427 and
     * FilesSubPanel.openDiffViewer). Without the annotation the JVM memory model does
     * not guarantee visibility across dispatcher boundaries (COR-5 fix).
     */
    @Volatile private var currentPrId: Int? = null
    @Volatile private var currentPr: BitbucketPrDetail? = null
    private var currentMergeStatus: BitbucketMergeStatus? = null
    private var loadJob: Job? = null

    // Per-PR repo coordinates. These are the OWNER of the currently-shown PR — distinct from
    // the project-default PluginSettings.bitbucketProjectKey/Slug because in multi-repo setups
    // the selected PR can belong to any of the configured repos. Used by Open-in-Browser (and
    // any other action that needs to hit the correct repo's Bitbucket URL space) so the URL
    // matches the repo badge shown in the list, not the default module.
    private var currentPrProjectKey: String? = null
    private var currentPrRepoSlug: String? = null

    // Card names
    private companion object {
        const val CARD_EMPTY = "empty"
        const val CARD_LOADING = "loading"
        const val CARD_DETAIL = "detail"

        private val SECONDARY_TEXT = StatusColors.SECONDARY_TEXT
        private val CARD_BG = StatusColors.CARD_BG
        private val BORDER_COLOR = StatusColors.BORDER
        private val LINK_COLOR = StatusColors.LINK
        private val STATUS_OPEN = StatusColors.OPEN
        private val STATUS_MERGED = StatusColors.MERGED
        private val STATUS_DECLINED = StatusColors.DECLINED
        private val APPROVED_COLOR = StatusColors.SUCCESS

        private val DATE_FORMAT = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(java.time.ZoneId.systemDefault())
    }

    // -- Empty state --
    private val emptyPanel = JPanel(BorderLayout()).apply {
        isOpaque = false
        add(JBLabel("Select a pull request to view details.").apply {
            foreground = JBUI.CurrentTheme.Label.disabledForeground()
            horizontalAlignment = SwingConstants.CENTER
            verticalAlignment = SwingConstants.CENTER
        }, BorderLayout.CENTER)
    }

    // -- Loading state --
    private val loadingPanel = JPanel(BorderLayout()).apply {
        isOpaque = false
        val loadingRow = JPanel(FlowLayout(FlowLayout.CENTER)).apply {
            isOpaque = false
            add(JBLabel(AnimatedIcon.Default()))
            add(JBLabel("Loading PR details...").apply {
                foreground = SECONDARY_TEXT
            })
        }
        add(loadingRow, BorderLayout.CENTER)
    }

    // -- Detail state components --
    private val detailPanel = JPanel(BorderLayout()).apply {
        isOpaque = false
    }


    // Back navigation callback — set by PrDashboardPanel
    var onBackClicked: (() -> Unit)? = null

    // Back link
    private val backLabel = JBLabel("\u2190 Back to list").apply {
        foreground = LINK_COLOR
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        font = font.deriveFont(JBUI.scale(11).toFloat())
        border = JBUI.Borders.emptyBottom(4)
    }

    // Header — title label with double-click-to-edit
    private val titleLabel = JBLabel("").apply {
        font = font.deriveFont(Font.BOLD, JBUI.scale(16).toFloat())
        foreground = JBColor.foreground()
    }
    private val titleEditField = JBTextField().apply {
        font = font.deriveFont(Font.BOLD, JBUI.scale(16).toFloat())
        isVisible = false
    }
    private var titleEditing = false
    private var currentUserApproved = false
    private val prIdLabel = JBLabel("").apply {
        font = font.deriveFont(Font.BOLD, JBUI.scale(12).toFloat())
        foreground = LINK_COLOR
    }
    private val branchLabel = JBLabel("").apply {
        font = font.deriveFont(JBUI.scale(11).toFloat())
        foreground = SECONDARY_TEXT
    }
    private val statusBadgeContainer = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
        isOpaque = false
    }
    private val reviewersLabel = JBLabel("").apply {
        font = font.deriveFont(JBUI.scale(11).toFloat())
        foreground = SECONDARY_TEXT
    }
    private val reviewersPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
        isOpaque = false
    }
    private val addReviewerLink = JBLabel("+ Add").apply {
        foreground = LINK_COLOR
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        font = font.deriveFont(JBUI.scale(11).toFloat())
    }
    private val buildStatusBadgeContainer = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
        isOpaque = false
    }
    private var buildStatusUrl: String? = null

    // Action buttons
    private val approveButton = JButton("Approve").apply {
        icon = AllIcons.Actions.Checked
        mnemonic = java.awt.event.KeyEvent.VK_A
    }
    private val mergeButton = JButton("Merge").apply {
        icon = AllIcons.Vcs.Merge
        mnemonic = java.awt.event.KeyEvent.VK_M
    }
    private val declineButton = JButton("Decline").apply {
        icon = AllIcons.Actions.Cancel
        mnemonic = java.awt.event.KeyEvent.VK_D
    }
    private val needsWorkButton = JButton("Needs Work").apply {
        icon = AllIcons.General.Warning
        mnemonic = java.awt.event.KeyEvent.VK_N
    }
    private val openInBrowserButton = JButton("Open in Browser").apply {
        icon = AllIcons.General.Web
    }

    // Content toggle buttons
    private val descriptionToggle = JToggleButton("Description")
    private val activityToggle = JToggleButton("Activity")
    private val filesToggle = JToggleButton("Files")
    private val commitsToggle = JToggleButton("Commits")
    private val commentsToggle = JToggleButton("Comments")
    private val aiReviewToggle = JToggleButton("AI Review")
    private val toggleGroup = ButtonGroup()

    // Content panels
    private val contentCards = JPanel(CardLayout()).apply {
        isOpaque = false
    }
    private val descriptionSubPanel = DescriptionSubPanel()
    private val activitySubPanel = ActivitySubPanel()
    private val filesSubPanel = FilesSubPanel()
    private val commitsSubPanel = CommitsSubPanel()
    /** Lazily created / replaced each time a new PR is loaded. */
    private var aiReviewTabPanel: AiReviewTabPanel? = null
    /** Lazily created / replaced each time a new PR is loaded. */
    private var commentsTabPanel: CommentsTabPanel? = null

    init {
        isOpaque = false
        background = JBColor.PanelBackground

        // Wire back link click
        backLabel.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                onBackClicked?.invoke()
            }
        })

        add(emptyPanel, CARD_EMPTY)
        add(loadingPanel, CARD_LOADING)

        buildDetailPanel()
        add(detailPanel, CARD_DETAIL)

        showEmpty()
    }

    // ---------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------

    fun showEmpty() {
        currentPrId = null
        currentPr = null
        currentPrProjectKey = null
        currentPrRepoSlug = null
        (layout as CardLayout).show(this, CARD_EMPTY)
    }

    fun showPr(prId: Int, projectKey: String? = null, repoSlug: String? = null) {
        if (prId == currentPrId) return
        currentPrId = prId
        currentPr = null
        currentPrProjectKey = projectKey
        currentPrRepoSlug = repoSlug
        loadJob?.cancel()

        (layout as CardLayout).show(this, CARD_LOADING)

        loadJob = scope.launch {
            val detailService = PrDetailService.getInstance(project)
            val prDetail = detailService.getDetail(prId)

            if (prDetail == null) {
                invokeLater {
                    if (currentPrId != prId) return@invokeLater
                    showEmpty()
                }
                return@launch
            }

            invokeLater {
                if (currentPrId != prId) return@invokeLater
                currentPr = prDetail
                renderPrHeader(prId, prDetail.title, prDetail.state,
                    prDetail.fromRef, prDetail.toRef)
                renderReviewers(prDetail)
                descriptionSubPanel.showDescription(prDetail.description)
                rebuildCommentsTab(prId)
                rebuildAiReviewTab(prId, prDetail)
                selectToggle(descriptionToggle)
                (layout as CardLayout).show(this@PrDetailPanel, CARD_DETAIL)
            }

            // Load activities in background
            val activities = detailService.getActivities(prId)
            invokeLater {
                if (currentPrId != prId) return@invokeLater
                activitySubPanel.showActivities(activities)
            }

            // Load changes in background
            val changes = detailService.getChanges(prId)
            invokeLater {
                if (currentPrId != prId) return@invokeLater
                filesSubPanel.showChanges(changes)
            }

            // Check merge preconditions in background
            val mergeStatus = PrActionService.getInstance(project).checkMergeStatus(prId)
            invokeLater {
                if (currentPrId != prId) return@invokeLater
                currentMergeStatus = mergeStatus
                updateMergeButtonState(mergeStatus)
            }

            // Fetch build status for the source branch's latest commit
            val commitId = prDetail.fromRef?.latestCommit
            if (!commitId.isNullOrBlank()) {
                val statuses = detailService.getBuildStatus(commitId)
                invokeLater {
                    if (currentPrId != prId) return@invokeLater
                    updateBuildStatusBadge(statuses)
                }
            }
        }
    }

    /**
     * Show a PR directly from a BitbucketPrDetail object (avoids re-fetch).
     *
     * Phase 5 T12: PrDashboardPanel's row-click handler invokes both
     * `WorkflowContextService.focusPr(...)` (canonical single-source-of-truth write that
     * cascades to Build + Quality tabs via `state.collect`) AND this `showPrDetail(...)`
     * call. Both paths are gated by the same row-click event and share the same `prDetail`,
     * so PrDetailPanel remains call-driven without a redundant `state.map { it.focusPr }`
     * collector that would create dual-control with the parent's call.
     */
    fun showPrDetail(pr: BitbucketPrDetail, projectKey: String? = null, repoSlug: String? = null) {
        currentPrId = pr.id
        currentPr = pr
        currentPrProjectKey = projectKey
        currentPrRepoSlug = repoSlug
        loadJob?.cancel()

        invokeLater {
            renderPrHeader(pr.id, pr.title, pr.state, pr.fromRef, pr.toRef)
            renderReviewers(pr)
            descriptionSubPanel.showDescription(pr.description)
            rebuildCommentsTab(pr.id)
            rebuildAiReviewTab(pr.id, pr)
            selectToggle(descriptionToggle)
            (layout as CardLayout).show(this@PrDetailPanel, CARD_DETAIL)
        }

        // Load activities and changes in background
        loadJob = scope.launch {
            val detailService = PrDetailService.getInstance(project)

            val activities = detailService.getActivities(pr.id)
            invokeLater {
                if (currentPrId != pr.id) return@invokeLater
                activitySubPanel.showActivities(activities)
            }

            val changes = detailService.getChanges(pr.id)
            invokeLater {
                if (currentPrId != pr.id) return@invokeLater
                filesSubPanel.showChanges(changes)
            }

            // Check merge preconditions in background
            val mergeStatus = PrActionService.getInstance(project).checkMergeStatus(pr.id)
            invokeLater {
                if (currentPrId != pr.id) return@invokeLater
                currentMergeStatus = mergeStatus
                updateMergeButtonState(mergeStatus)
            }

            // Fetch build status for the source branch's latest commit
            val commitId = pr.fromRef?.latestCommit
            if (!commitId.isNullOrBlank()) {
                val statuses = detailService.getBuildStatus(commitId)
                invokeLater {
                    if (currentPrId != pr.id) return@invokeLater
                    updateBuildStatusBadge(statuses)
                }
            }
        }
    }

    /**
     * Creates (or replaces) the AI Review tab panel for the given PR.
     * Must be called on the EDT.
     */
    private fun rebuildAiReviewTab(prId: Int, prDetail: BitbucketPrDetail) {
        // Dispose the old panel through the Disposer so its registration under this
        // PrDetailPanel is removed too — otherwise per-PR-switch registrations would
        // accumulate under the parent until the parent itself disposes.
        aiReviewTabPanel?.let {
            contentCards.remove(it)
            Disposer.dispose(it)
        }
        // Use the PR's OWN coordinates (threaded in from PrDashboardPanel via showPr/showPrDetail)
        // so the review tab talks to the correct repo's Bitbucket. The name-based RepoConfig
        // lookup is a secondary fallback for cases where showPrDetail was called without an
        // explicit projectKey (direct callers, older paths). The scalar default is the last
        // resort for single-repo projects.
        val pluginSettings = PluginSettings.getInstance(project)
        val byName = currentPr?.repoName?.takeIf { it.isNotBlank() }?.let { name ->
            pluginSettings.getRepos().find { it.displayLabel == name }
        }
        val projectKey = currentPrProjectKey
            ?: byName?.bitbucketProjectKey
            ?: pluginSettings.state.bitbucketProjectKey.orEmpty()
        val repoSlug = currentPrRepoSlug
            ?: byName?.bitbucketRepoSlug
            ?: pluginSettings.state.bitbucketRepoSlug.orEmpty()
        val newPanel = AiReviewTabPanel(
            project = project,
            projectKey = projectKey,
            repoSlug = repoSlug,
            prId = prId,
            onRunReviewClicked = { runAiReview(projectKey, repoSlug, prId, prDetail) },
        )
        aiReviewTabPanel = newPanel
        Disposer.register(this, newPanel)
        val layout = contentCards.layout as CardLayout
        contentCards.add(newPanel, "aiReview")
        layout.show(contentCards, "description")   // keep current view unchanged
    }

    private fun runAiReview(projectKey: String, repoSlug: String, prId: Int, prDetail: BitbucketPrDetail) {
        val confirmed = Messages.showYesNoDialog(
            project,
            "Start a new agent session to review PR-$prId?\n(You'll be switched to the Agent tab.)",
            "Run AI Review",
            Messages.getQuestionIcon(),
        ) == Messages.YES
        if (!confirmed) return

        scope.launch {
            val bitbucketService = project.getService(BitbucketService::class.java)

            // Fetch diff and changed files
            val diffResult = bitbucketService?.getPullRequestDiff(prId)
            val changesResult = bitbucketService?.getPullRequestChanges(prId)

            if (diffResult == null || diffResult.isError) {
                invokeLater {
                    Messages.showErrorDialog(
                        project,
                        "Could not fetch PR diff: ${diffResult?.summary ?: "service unavailable"}",
                        "AI Review Failed",
                    )
                }
                return@launch
            }

            // Guard against isError=false but data=null (pullrequest:F-6 — forced !! NPE).
            val diff = diffResult.data ?: run {
                invokeLater {
                    Messages.showErrorDialog(
                        project,
                        "PR diff was empty — cannot start AI review.",
                        "AI Review Failed",
                    )
                }
                return@launch
            }
            val changedFiles = if (changesResult != null && !changesResult.isError) {
                changesResult.data?.map { it.path } ?: emptyList()
            } else {
                emptyList()
            }

            val sourceBranch = prDetail.fromRef?.displayId ?: ""
            val targetBranch = prDetail.toRef?.displayId ?: ""
            val prAuthor = prDetail.author?.user?.displayName ?: prDetail.author?.user?.name ?: "unknown"
            val reviewerNames = prDetail.reviewers.map { it.user.displayName.ifBlank { it.user.name } }

            val sessionId = UUID.randomUUID().toString()
            val prompt = PrReviewTaskBuilder().build(
                projectKey = projectKey,
                repoSlug = repoSlug,
                prId = prId,
                prTitle = prDetail.title,
                prAuthor = prAuthor,
                sourceBranch = sourceBranch,
                targetBranch = targetBranch,
                reviewers = reviewerNames,
                changedFiles = changedFiles,
                diff = diff,
                jiraTicket = null,   // Phase 5 polish: wire Jira ticket resolution
                sessionId = sessionId,
            )

            // Register in session registry before starting the agent
            project.getService(PrReviewSessionRegistry::class.java)?.register(
                "$projectKey/$repoSlug/PR-$prId", sessionId, "running",
            )

            // Start the agent session and switch to the agent tool window
            invokeLater {
                val redirect = AgentChatRedirect.getInstance()
                if (redirect == null) {
                    Messages.showErrorDialog(
                        project,
                        "Agent is not available. Please open the AI Agent tab first.",
                        "AI Review Failed",
                    )
                    return@invokeLater
                }
                redirect.startPrReviewSession(
                    project = project,
                    persona = "code-reviewer",
                    initialMessage = prompt,
                    sessionTag = "pr-review:$projectKey/$repoSlug/PR-$prId",
                )
                // Switch to the Workflow tool window so user sees the agent working
                ToolWindowManager.getInstance(project).getToolWindow("Workflow")?.activate(null)
                // Notify the AI Review tab so it binds to the new session
                aiReviewTabPanel?.onSessionChanged()
            }
        }
    }

    /**
     * Creates (or replaces) the Comments tab panel for the given PR.
     * Must be called on the EDT.
     */
    private fun rebuildCommentsTab(prId: Int) {
        // Dispose the old panel through the Disposer (see [rebuildAiReviewTab]) so the
        // child registration under this PrDetailPanel is unregistered on each PR switch.
        commentsTabPanel?.let {
            contentCards.remove(it)
            Disposer.dispose(it)
        }
        // Same per-PR coordinate resolution as [rebuildAiReviewTab] — see that method's note.
        val pluginSettings = PluginSettings.getInstance(project)
        val byName = currentPr?.repoName?.takeIf { it.isNotBlank() }?.let { name ->
            pluginSettings.getRepos().find { it.displayLabel == name }
        }
        val projectKey = currentPrProjectKey
            ?: byName?.bitbucketProjectKey
            ?: pluginSettings.state.bitbucketProjectKey.orEmpty()
        val repoSlug = currentPrRepoSlug
            ?: byName?.bitbucketRepoSlug
            ?: pluginSettings.state.bitbucketRepoSlug.orEmpty()
        val bitbucketService = project.getService(BitbucketService::class.java)
        val newTab = CommentsTabPanel(
            project = project,
            service = bitbucketService,
            projectKey = projectKey,
            repoSlug = repoSlug,
            prId = prId,
        )
        commentsTabPanel = newTab
        Disposer.register(this, newTab)
        // Replace or add the comments card
        val layout = contentCards.layout as CardLayout
        contentCards.add(newTab, "comments")
        layout.show(contentCards, "description")   // keep current view unchanged
    }

    override fun dispose() {
        loadJob?.cancel()
        scope.cancel()
        // The tab panels are registered as children under this Disposable, so the
        // platform would dispose them automatically; we dispose them explicitly here
        // (Disposer.dispose is idempotent) to keep teardown deterministic and to null
        // the references. Disposing an already-disposed child is a safe no-op.
        commentsTabPanel?.let { Disposer.dispose(it) }
        commentsTabPanel = null
        aiReviewTabPanel?.let { Disposer.dispose(it) }
        aiReviewTabPanel = null
    }

    // ---------------------------------------------------------------
    // Detail panel construction
    // ---------------------------------------------------------------

    private fun buildDetailPanel() {
        val contentPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(12, 16)
        }

        // Back navigation link
        val backRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(20))
            alignmentX = Component.LEFT_ALIGNMENT
        }
        backRow.add(backLabel)
        contentPanel.add(backRow)

        // Title + PR ID
        val headerSection = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyBottom(4)
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(60))
            alignmentX = Component.LEFT_ALIGNMENT
        }
        val titleContainer = JPanel(CardLayout()).apply {
            isOpaque = false
        }
        titleContainer.add(titleLabel, "label")
        titleContainer.add(titleEditField, "edit")
        headerSection.add(titleContainer, BorderLayout.NORTH)

        // Double-click title label to enter edit mode
        titleLabel.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && currentPr?.state.equals(PrState.OPEN, ignoreCase = true)) {
                    enterTitleEditMode(titleContainer)
                }
            }
        })
        // Enter to save, Escape to cancel
        titleEditField.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when (e.keyCode) {
                    KeyEvent.VK_ENTER -> saveTitleEdit(titleContainer)
                    KeyEvent.VK_ESCAPE -> cancelTitleEdit(titleContainer)
                }
            }
        })
        // Focus lost saves
        titleEditField.addFocusListener(object : FocusAdapter() {
            override fun focusLost(e: FocusEvent) {
                if (titleEditing) saveTitleEdit(titleContainer)
            }
        })
        val metaRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
            isOpaque = false
        }
        metaRow.add(prIdLabel)
        metaRow.add(statusBadgeContainer)
        metaRow.add(branchLabel)
        headerSection.add(metaRow, BorderLayout.CENTER)
        contentPanel.add(headerSection)

        // Reviewers row
        val reviewersRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
            isOpaque = false
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(28))
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.emptyBottom(4)
        }
        reviewersRow.add(reviewersLabel)
        reviewersRow.add(reviewersPanel)
        reviewersRow.add(addReviewerLink)
        contentPanel.add(reviewersRow)

        // Build status row
        val buildStatusRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(24))
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.emptyBottom(4)
        }
        buildStatusRow.add(buildStatusBadgeContainer)
        contentPanel.add(buildStatusRow)

        // Action buttons row
        val actionsRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            isOpaque = false
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(36))
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.emptyBottom(8)
        }
        actionsRow.add(approveButton)
        actionsRow.add(needsWorkButton)
        actionsRow.add(mergeButton)
        actionsRow.add(declineButton)
        actionsRow.add(openInBrowserButton)
        contentPanel.add(actionsRow)

        // Wire button actions
        setupActionButtons()

        // Toggle buttons row
        val toggleRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(32))
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.empty(0, 0, 8, 0)
        }
        toggleGroup.add(descriptionToggle)
        toggleGroup.add(activityToggle)
        toggleGroup.add(filesToggle)
        toggleGroup.add(commitsToggle)
        toggleGroup.add(commentsToggle)
        toggleGroup.add(aiReviewToggle)
        toggleRow.add(descriptionToggle)
        toggleRow.add(activityToggle)
        toggleRow.add(filesToggle)
        toggleRow.add(commitsToggle)
        toggleRow.add(commentsToggle)
        toggleRow.add(aiReviewToggle)
        contentPanel.add(toggleRow)

        // Content cards
        contentCards.add(descriptionSubPanel, "description")
        contentCards.add(activitySubPanel, "activity")
        contentCards.add(filesSubPanel, "files")
        contentCards.add(commitsSubPanel, "commits")
        // "aiReview" card is added lazily when a PR is loaded (see rebuildAiReviewTab)
        // "comments" card is added lazily when a PR is loaded (see rebuildCommentsTab)
        contentCards.alignmentX = Component.LEFT_ALIGNMENT
        contentPanel.add(contentCards)

        // Toggle listeners
        descriptionToggle.addActionListener { (contentCards.layout as CardLayout).show(contentCards, "description") }
        activityToggle.addActionListener { (contentCards.layout as CardLayout).show(contentCards, "activity") }
        filesToggle.addActionListener { (contentCards.layout as CardLayout).show(contentCards, "files") }
        commitsToggle.addActionListener {
            (contentCards.layout as CardLayout).show(contentCards, "commits")
            currentPrId?.let { commitsSubPanel.showCommits(it) }
        }
        commentsToggle.addActionListener {
            (contentCards.layout as CardLayout).show(contentCards, "comments")
            commentsTabPanel?.triggerRefresh()
        }
        aiReviewToggle.addActionListener { (contentCards.layout as CardLayout).show(contentCards, "aiReview") }

        val scrollPane = JBScrollPane(contentPanel).apply {
            border = JBUI.Borders.empty()
            isOpaque = false
            viewport.isOpaque = false
        }
        // Phase 5 T15: ReadOnly banner sits OUTSIDE the scroll pane so it stays visible
        // even when the user scrolls through a long PR (spec §7.1).
        detailPanel.add(readOnlyBanner, BorderLayout.NORTH)
        detailPanel.add(scrollPane, BorderLayout.CENTER)
    }

    private fun setupActionButtons() {
        approveButton.addActionListener {
            val prId = currentPrId ?: return@addActionListener
            scope.launch {
                try {
                    val actionService = PrActionService.getInstance(project)
                    if (currentUserApproved) {
                        // Unapprove
                        val result = actionService.unapprove(prId)
                        invokeLater {
                            when (result) {
                                is ApiResult.Success -> {
                                    currentUserApproved = false
                                    approveButton.text = "Approve"
                                    approveButton.icon = AllIcons.Actions.Checked
                                    refreshCurrentPr()
                                }
                                is ApiResult.Error -> showNotification("Unapprove failed: ${result.message}")
                            }
                        }
                    } else {
                        // Approve
                        val result = actionService.approve(prId)
                        invokeLater {
                            when (result) {
                                is ApiResult.Success -> {
                                    currentUserApproved = true
                                    approveButton.text = "Unapprove"
                                    approveButton.icon = AllIcons.Actions.Undo
                                    refreshCurrentPr()
                                }
                                is ApiResult.Error -> showNotification("Approve failed: ${result.message}")
                            }
                        }
                    }
                } catch (e: Exception) {
                    invokeLater {
                        WorkflowNotificationService.getInstance(project).notifyError(
                            WorkflowNotificationService.GROUP_BUILD,
                            "PR Action Failed",
                            "PR action failed: ${e.message}"
                        )
                    }
                }
            }
        }

        mergeButton.addActionListener {
            val prId = currentPrId ?: return@addActionListener
            val mergeStatus = currentMergeStatus

            // Show merge options dialog
            scope.launch {
                val strategies = PrActionService.getInstance(project).getMergeStrategies()
                invokeLater {
                    val dialog = MergeOptionsDialog(
                        project = project,
                        prId = prId,
                        strategies = strategies,
                        mergeStatus = mergeStatus
                    )
                    if (!dialog.showAndGet()) return@invokeLater

                    scope.launch {
                        // version dropped in PR 3 of the 2026-05-07 write-ops fix plan —
                        // PrActionService.merge now refetches inside mergePullRequestWithRetry.
                        val mergeResult = PrActionService.getInstance(project).merge(
                            prId = prId,
                            strategyId = dialog.selectedStrategyId,
                            deleteSourceBranch = dialog.deleteSourceBranch,
                            commitMessage = dialog.commitMessage.takeIf { it.isNotBlank() }
                        )
                        invokeLater {
                            when (mergeResult) {
                                is ApiResult.Success -> {
                                    // Merge succeeded — disable all mutating actions
                                    mergeButton.isEnabled = false
                                    approveButton.isEnabled = false
                                    needsWorkButton.isEnabled = false
                                    declineButton.isEnabled = false
                                }
                                is ApiResult.Error -> {
                                    // Merge failed — surface the error and leave buttons
                                    // enabled so the user can retry (mirrors declineButton
                                    // handler pattern, COR-1 fix).
                                    WorkflowNotificationService.getInstance(project).notifyError(
                                        WorkflowNotificationService.GROUP_BUILD,
                                        "Merge Failed",
                                        mergeResult.message.ifBlank { "Could not merge PR #$prId — try refreshing." }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        declineButton.addActionListener {
            val prId = currentPrId ?: return@addActionListener
            val confirm = com.intellij.openapi.ui.Messages.showYesNoDialog(
                this,
                "Decline PR #$prId?",
                "Confirm Decline",
                com.intellij.openapi.ui.Messages.getWarningIcon()
            )
            if (confirm != com.intellij.openapi.ui.Messages.YES) return@addActionListener
            scope.launch {
                val result = PrActionService.getInstance(project).decline(prId)
                invokeLater {
                    if (result.isError) {
                        val errMsg = (result as? com.workflow.orchestrator.core.model.ApiResult.Error)
                            ?.message ?: "Could not decline PR #$prId — try refreshing."
                        WorkflowNotificationService.getInstance(project).notifyError(
                            WorkflowNotificationService.GROUP_BUILD,
                            "Decline Failed",
                            errMsg
                        )
                    } else {
                        mergeButton.isEnabled = false
                        approveButton.isEnabled = false
                        needsWorkButton.isEnabled = false
                        declineButton.isEnabled = false
                    }
                }
            }
        }

        needsWorkButton.addActionListener {
            val prId = currentPrId ?: return@addActionListener
            val pr = currentPr ?: return@addActionListener
            scope.launch {
                // Resolve the actual authenticated user, not the PR author
                val currentUser = resolveCurrentUsername()
                if (currentUser.isNullOrBlank()) {
                    invokeLater {
                        showNotification("Cannot determine current user for Needs Work")
                    }
                    return@launch
                }
                try {
                    val result = PrActionService.getInstance(project).setNeedsWork(prId, currentUser)
                    invokeLater {
                        when (result) {
                            is ApiResult.Success -> {
                                needsWorkButton.text = "Needs Work Set"
                                needsWorkButton.isEnabled = false
                                refreshCurrentPr()
                            }
                            is ApiResult.Error -> showNotification("Needs Work failed: ${result.message}")
                        }
                    }
                } catch (e: Exception) {
                    invokeLater {
                        showNotification("Needs Work failed: ${e.message}")
                    }
                }
            }
        }

        addReviewerLink.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                showAddReviewerPopup(addReviewerLink)
            }
        })

        openInBrowserButton.addActionListener {
            val prId = currentPrId ?: return@addActionListener
            val connSettings = ConnectionSettings.getInstance().state
            val bitbucketUrl = connSettings.bitbucketUrl.trimEnd('/')
            // Prefer the PR's OWN repo coordinates (from the list-selection → detail hand-off).
            // Fall back to resolving via repoName against configured repos, then to the scalar
            // default settings for single-repo projects.
            val pluginSettings = PluginSettings.getInstance(project)
            val repoConfigByName = currentPr?.repoName?.takeIf { it.isNotBlank() }?.let { name ->
                pluginSettings.getRepos().find { it.displayLabel == name }
            }
            val projectKey = currentPrProjectKey
                ?: repoConfigByName?.bitbucketProjectKey
                ?: pluginSettings.state.bitbucketProjectKey.orEmpty()
            val repoSlug = currentPrRepoSlug
                ?: repoConfigByName?.bitbucketRepoSlug
                ?: pluginSettings.state.bitbucketRepoSlug.orEmpty()
            if (bitbucketUrl.isNotBlank() && projectKey.isNotBlank() && repoSlug.isNotBlank()) {
                log.info("[PR:Detail] Open in Browser: pr=$prId repo=$projectKey/$repoSlug (pr.repoName='${currentPr?.repoName}')")
                BrowserUtil.browse("$bitbucketUrl/projects/$projectKey/repos/$repoSlug/pull-requests/$prId")
            } else {
                log.warn("[PR:Detail] Open in Browser skipped — missing coordinates: url='$bitbucketUrl' project='$projectKey' repo='$repoSlug'")
            }
        }
    }

    private fun selectToggle(toggle: JToggleButton) {
        toggle.isSelected = true
        val cardName = when (toggle) {
            descriptionToggle -> "description"
            activityToggle -> "activity"
            filesToggle -> "files"
            commitsToggle -> "commits"
            commentsToggle -> "comments"
            aiReviewToggle -> "aiReview"
            else -> "description"
        }
        (contentCards.layout as CardLayout).show(contentCards, cardName)
    }

    // ---------------------------------------------------------------
    // Merge status helpers
    // ---------------------------------------------------------------

    private fun updateMergeButtonState(mergeStatus: BitbucketMergeStatus?) {
        val state = MergeButtonStateDeriver.derive(mergeStatus)
        when (state.icon) {
            MergeButtonStateDeriver.IconKind.WARNING -> mergeButton.icon = AllIcons.General.Warning
            MergeButtonStateDeriver.IconKind.MERGE -> mergeButton.icon = AllIcons.Vcs.Merge
            // For canMerge, mergeButton.isEnabled is already set by renderPrHeader based on PR state.
            MergeButtonStateDeriver.IconKind.UNCHANGED -> {}
        }
        if (state.forceDisable) mergeButton.isEnabled = false
        mergeButton.toolTipText = state.tooltip
    }

    // ---------------------------------------------------------------
    // Rendering helpers
    // ---------------------------------------------------------------

    private fun renderPrHeader(
        prId: Int,
        title: String,
        state: String,
        fromRef: BitbucketPrRef?,
        toRef: BitbucketPrRef?
    ) {
        titleLabel.text = title
        prIdLabel.text = "PR #$prId"
        branchLabel.text = "${fromRef?.displayId ?: "?"} \u2192 ${toRef?.displayId ?: "?"}"

        statusBadgeContainer.removeAll()
        statusBadgeContainer.add(createStatusBadge(state))
        statusBadgeContainer.revalidate()

        // Enable/disable action buttons based on state
        val isOpen = state.equals(PrState.OPEN, ignoreCase = true)
        approveButton.isEnabled = isOpen
        needsWorkButton.isEnabled = isOpen
        mergeButton.isEnabled = isOpen
        declineButton.isEnabled = isOpen
        addReviewerLink.isVisible = isOpen

        // Check current user's approval status for approve/unapprove toggle
        if (isOpen) {
            scope.launch {
                val currentUsername = resolveCurrentUsername()
                val pr = currentPr
                val approved = if (currentUsername != null && pr != null) {
                    pr.reviewers.any { it.user.name == currentUsername && it.approved }
                } else false
                invokeLater {
                    currentUserApproved = approved
                    if (approved) {
                        approveButton.text = "Unapprove"
                        approveButton.icon = AllIcons.Actions.Undo
                    } else {
                        approveButton.text = "Approve"
                        approveButton.icon = AllIcons.Actions.Checked
                    }
                }
            }
        } else {
            currentUserApproved = false
            approveButton.text = "Approve"
            approveButton.icon = AllIcons.Actions.Checked
        }
    }

    /**
     * Resolve the current Bitbucket username via API (cached after first successful call).
     *
     * @Volatile ensures writes from the IO coroutine (resolveCurrentUsername) are
     * immediately visible to reads on the EDT (renderReviewers approval-status check),
     * preventing stale-reads across the EDT ↔ IO dispatcher boundary (F-12 fix).
     */
    @Volatile private var cachedUsername: String? = null

    private suspend fun resolveCurrentUsername(): String? {
        cachedUsername?.let { return it }
        val client = clientCache.get() ?: return null
        return when (val result = client.getCurrentUsername()) {
            is ApiResult.Success -> { cachedUsername = result.data; result.data }
            is ApiResult.Error -> null
        }
    }

    private fun renderReviewers(pr: BitbucketPrDetail) {
        reviewersPanel.removeAll()
        val isOpen = pr.state.equals(PrState.OPEN, ignoreCase = true)

        if (pr.reviewers.isEmpty()) {
            reviewersLabel.text = "No reviewers assigned"
            reviewersPanel.revalidate()
            reviewersPanel.repaint()
            return
        }

        reviewersLabel.text = "Reviewers:"

        for (reviewer in pr.reviewers) {
            val name = reviewer.user.displayName.ifBlank { reviewer.user.name }
            val statusIcon = when {
                reviewer.approved -> "\u2713"
                reviewer.status.equals("NEEDS_WORK", ignoreCase = true) -> "\u2718"
                else -> "\u25CB"
            }
            val statusColor = when {
                reviewer.approved -> APPROVED_COLOR
                reviewer.status.equals("NEEDS_WORK", ignoreCase = true) -> StatusColors.WARNING
                else -> SECONDARY_TEXT
            }

            val chipPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(2), 0)).apply {
                isOpaque = false
            }
            chipPanel.add(JBLabel("$statusIcon $name").apply {
                font = font.deriveFont(JBUI.scale(11).toFloat())
                foreground = statusColor
            })

            // Add remove "x" link for open PRs
            if (isOpen) {
                val removeLabel = JBLabel("\u00D7").apply {
                    foreground = StatusColors.ERROR
                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    font = font.deriveFont(Font.BOLD, JBUI.scale(11).toFloat())
                    toolTipText = "Remove $name"
                }
                val reviewerUsername = reviewer.user.name
                removeLabel.addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) {
                        val confirm = com.intellij.openapi.ui.Messages.showYesNoDialog(
                            this@PrDetailPanel,
                            "Remove reviewer '$name' from this PR?",
                            "Remove Reviewer",
                            com.intellij.openapi.ui.Messages.getWarningIcon()
                        )
                        if (confirm != com.intellij.openapi.ui.Messages.YES) return
                        val prId = currentPrId ?: return
                        scope.launch {
                            val result = PrActionService.getInstance(project).removeReviewer(prId, reviewerUsername)
                            invokeLater {
                                when (result) {
                                    is ApiResult.Success -> refreshCurrentPr()
                                    is ApiResult.Error -> showNotification("Failed to remove reviewer: ${result.message}")
                                }
                            }
                        }
                    }
                })
                chipPanel.add(removeLabel)
            }

            reviewersPanel.add(chipPanel)
        }

        reviewersPanel.revalidate()
        reviewersPanel.repaint()
    }

    private fun enterTitleEditMode(titleContainer: JPanel) {
        titleEditing = true
        titleEditField.text = titleLabel.text
        (titleContainer.layout as CardLayout).show(titleContainer, "edit")
        titleEditField.requestFocusInWindow()
        titleEditField.selectAll()
    }

    private fun cancelTitleEdit(titleContainer: JPanel) {
        titleEditing = false
        (titleContainer.layout as CardLayout).show(titleContainer, "label")
    }

    private fun saveTitleEdit(titleContainer: JPanel) {
        val newTitle = titleEditField.text.trim()
        titleEditing = false
        (titleContainer.layout as CardLayout).show(titleContainer, "label")

        if (newTitle.isBlank() || newTitle == titleLabel.text) return
        val prId = currentPrId ?: return

        titleLabel.text = newTitle
        scope.launch {
            val result = PrActionService.getInstance(project).updateTitle(prId, newTitle)
            invokeLater {
                when (result) {
                    is ApiResult.Success -> {
                        // Refresh to get updated version
                        refreshCurrentPr()
                    }
                    is ApiResult.Error -> {
                        // Revert title on failure
                        titleLabel.text = currentPr?.title ?: titleLabel.text
                        showNotification("Failed to update title: ${result.message}")
                    }
                }
            }
        }
    }

    /**
     * Re-fetch the current PR and re-render reviewers.
     */
    private fun refreshCurrentPr() {
        val prId = currentPrId ?: return
        scope.launch {
            val detailService = PrDetailService.getInstance(project)
            val prDetail = detailService.getDetail(prId)
            if (prDetail != null) {
                invokeLater {
                    if (currentPrId != prId) return@invokeLater
                    currentPr = prDetail
                    renderReviewers(prDetail)
                }
            }
        }
    }

    private fun showNotification(message: String) {
        WorkflowNotificationService.getInstance(project).notifyError(
            WorkflowNotificationService.GROUP_BUILD,
            "PR Error",
            message
        )
    }

    /**
     * Shows a popup with user search for adding reviewers to an existing PR.
     * Debounces input by 300ms, queries BitbucketBranchClient.getUsers() filtered
     * by the *target PR's* repo so the dropdown only suggests users with effective
     * REPO_READ on that repo (matches the Bitbucket web UI). Audit P1 bonus fix
     * bundled with PR 3 of the 2026-05-07 write-ops fix plan.
     */
    private fun showAddReviewerPopup(relativeTo: Component) {
        showUserSearchPopup(
            relativeTo = relativeTo,
            projectKey = currentPrProjectKey,
            repoSlug = currentPrRepoSlug,
        ) { user, _ ->
            val prId = currentPrId ?: return@showUserSearchPopup
            scope.launch {
                val result = PrActionService.getInstance(project).addReviewer(prId, user.name)
                invokeLater {
                    when (result) {
                        is ApiResult.Success -> refreshCurrentPr()
                        is ApiResult.Error -> showNotification("Failed to add reviewer: ${result.message}")
                    }
                }
            }
        }
    }

    /**
     * Shared user search popup used by both create-PR and detail-panel reviewer addition.
     * @param excludeUsernames usernames to filter out of search results (e.g., already selected)
     * @param projectKey optional Bitbucket project key — when provided alongside [repoSlug],
     *   the user search is filtered to users with effective REPO_READ on that repo
     *   (matches the web UI's reviewer-picker). Pass null for the legacy global search
     *   (e.g. the create-PR dialog where the repo isn't picked yet).
     * @param repoSlug optional Bitbucket repo slug — see [projectKey].
     * @param onUserSelected callback receiving the selected user and the popup (for dismissal)
     */
    private fun showUserSearchPopup(
        relativeTo: Component,
        excludeUsernames: Set<String> = emptySet(),
        projectKey: String? = null,
        repoSlug: String? = null,
        onUserSelected: (BitbucketUser, com.intellij.openapi.ui.popup.JBPopup) -> Unit
    ) {
        val popupContent = JPanel(BorderLayout()).apply {
            preferredSize = Dimension(JBUI.scale(260), JBUI.scale(200))
            border = JBUI.Borders.empty(8)
        }

        val searchField = JBTextField().apply {
            emptyText.text = "Search users..."
        }

        val userListModel = DefaultListModel<BitbucketUser>()
        val userList = JBList(userListModel).apply {
            cellRenderer = object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean
                ): Component {
                    super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                    val user = value as? BitbucketUser
                    text = if (user != null) {
                        val display = user.displayName.ifBlank { user.name }
                        "$display (${user.name})"
                    } else ""
                    return this
                }
            }
        }

        popupContent.add(searchField, BorderLayout.NORTH)
        popupContent.add(JBScrollPane(userList).apply {
            border = JBUI.Borders.emptyTop(4)
        }, BorderLayout.CENTER)

        val popup = com.intellij.openapi.ui.popup.JBPopupFactory.getInstance()
            .createComponentPopupBuilder(popupContent, searchField)
            .setRequestFocus(true)
            .setFocusable(true)
            .setMovable(true)
            .setTitle("Add Reviewer")
            .createPopup()

        // Debounced search
        var searchJob: Job? = null
        searchField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = triggerSearch()
            override fun removeUpdate(e: DocumentEvent) = triggerSearch()
            override fun changedUpdate(e: DocumentEvent) = triggerSearch()

            private fun triggerSearch() {
                searchJob?.cancel()
                val query = searchField.text.trim()
                if (query.length < 2) {
                    userListModel.clear()
                    return
                }
                searchJob = scope.launch {
                    delay(300) // debounce
                    val client = clientCache.get() ?: return@launch
                    when (val result = client.getUsers(query, projectKey, repoSlug)) {
                        is ApiResult.Success -> {
                            invokeLater {
                                userListModel.clear()
                                result.data
                                    .filter { it.name !in excludeUsernames }
                                    .forEach { userListModel.addElement(it) }
                            }
                        }
                        is ApiResult.Error -> { /* ignore search errors */ }
                    }
                }
            }
        })

        // Click to select user
        userList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 1) {
                    val selected = userList.selectedValue ?: return
                    popup.cancel()
                    onUserSelected(selected, popup)
                }
            }
        })

        popup.showUnderneathOf(relativeTo)
    }

    private fun createStatusBadge(status: String): JPanel {
        val color = when (status.uppercase()) {
            PrState.OPEN -> STATUS_OPEN
            PrState.MERGED -> STATUS_MERGED
            PrState.DECLINED -> STATUS_DECLINED
            else -> SECONDARY_TEXT
        }
        val text = status.uppercase()

        return object : JPanel() {
            init {
                isOpaque = false
                val fm = getFontMetrics(font.deriveFont(Font.BOLD, JBUI.scale(10).toFloat()))
                val textW = fm.stringWidth(text)
                preferredSize = Dimension(
                    textW + JBUI.scale(12),
                    fm.height + JBUI.scale(6)
                )
            }

            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                val g2 = g.create() as Graphics2D
                com.workflow.orchestrator.core.ui.RenderingUtils.applyDesktopHints(g2)
                g2.color = color
                g2.fill(RoundRectangle2D.Float(
                    0f, 0f, width.toFloat(), height.toFloat(),
                    JBUI.scale(4).toFloat(), JBUI.scale(4).toFloat()
                ))
                g2.color = JBColor.WHITE
                g2.font = font.deriveFont(Font.BOLD, JBUI.scale(10).toFloat())
                val fm = g2.fontMetrics
                val textX = (width - fm.stringWidth(text)) / 2
                val textY = (height + fm.ascent - fm.descent) / 2
                g2.drawString(text, textX, textY)
                g2.dispose()
            }
        }
    }

    private fun updateBuildStatusBadge(statuses: List<BitbucketBuildStatus>) {
        val badge = BuildStatusBadgeDeriver.derive(statuses)
        // Store URL of the most relevant build status for click-to-open
        buildStatusUrl = badge.url

        buildStatusBadgeContainer.removeAll()
        buildStatusBadgeContainer.add(createBuildStatusBadge(badge.text, badge.color))
        buildStatusBadgeContainer.revalidate()
        buildStatusBadgeContainer.repaint()
    }

    private fun createBuildStatusBadge(text: String, color: JBColor): JPanel {
        val icon = when {
            text.contains("Passed") -> "\u2713 "
            text.contains("Failed") -> "\u2717 "
            text.contains("Building") -> "\u25B6 "
            else -> ""
        }
        val displayText = "$icon$text"

        return object : JPanel() {
            init {
                isOpaque = false
                cursor = if (buildStatusUrl?.isNotBlank() == true)
                    Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else Cursor.getDefaultCursor()
                val fm = getFontMetrics(font.deriveFont(Font.BOLD, JBUI.scale(10).toFloat()))
                val textW = fm.stringWidth(displayText)
                preferredSize = Dimension(
                    textW + JBUI.scale(12),
                    fm.height + JBUI.scale(6)
                )
                toolTipText = if (buildStatusUrl?.isNotBlank() == true) "Click to open build" else null
                addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent?) {
                        val url = buildStatusUrl
                        if (!url.isNullOrBlank()) {
                            BrowserUtil.browse(url)
                        }
                    }
                })
            }

            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                val g2 = g.create() as Graphics2D
                com.workflow.orchestrator.core.ui.RenderingUtils.applyDesktopHints(g2)
                g2.color = color
                g2.fill(RoundRectangle2D.Float(
                    0f, 0f, width.toFloat(), height.toFloat(),
                    JBUI.scale(4).toFloat(), JBUI.scale(4).toFloat()
                ))
                g2.color = JBColor.WHITE
                g2.font = font.deriveFont(Font.BOLD, JBUI.scale(10).toFloat())
                val fm = g2.fontMetrics
                val textX = (width - fm.stringWidth(displayText)) / 2
                val textY = (height + fm.ascent - fm.descent) / 2
                g2.drawString(displayText, textX, textY)
                g2.dispose()
            }
        }
    }

    // ---------------------------------------------------------------
    // Description sub-panel
    // ---------------------------------------------------------------

    private inner class DescriptionSubPanel : JPanel(BorderLayout()) {
        private val descriptionPane = JEditorPane().apply {
            contentType = "text/html"
            isEditable = false
            isOpaque = false
            border = JBUI.Borders.empty(8)
            addHyperlinkListener { e ->
                if (e.eventType == HyperlinkEvent.EventType.ACTIVATED) {
                    BrowserUtil.browse(e.url)
                }
            }
        }
        private val editArea = JBTextArea().apply {
            lineWrap = true
            wrapStyleWord = true
            isEditable = true
            font = font.deriveFont(JBUI.scale(12).toFloat())
            border = JBUI.Borders.empty(8)
        }
        private val editButton = JButton("Edit").apply {
            icon = AllIcons.Actions.Edit
            mnemonic = java.awt.event.KeyEvent.VK_E
        }
        private val updateButton = JButton("Update").apply {
            mnemonic = java.awt.event.KeyEvent.VK_U
        }
        private val cancelEditButton = JButton("Cancel").apply {
            mnemonic = java.awt.event.KeyEvent.VK_C
        }
        private val enhanceWithAiButton = JButton("Enhance with AI").apply {
            icon = AllIcons.Actions.Lightning
            toolTipText = "Use AI to generate or enhance the PR description"
            addActionListener { enhanceWithAi() }
        }

        private val emptyDescLabel = JBLabel("No description provided.").apply {
            foreground = JBUI.CurrentTheme.Label.disabledForeground()
            horizontalAlignment = SwingConstants.CENTER
            border = JBUI.Borders.emptyTop(20)
        }

        private var isEditing = false
        private var currentDescription: String? = null

        init {
            isOpaque = false

            editButton.addActionListener { enterEditMode() }
            cancelEditButton.addActionListener { exitEditMode() }
            updateButton.addActionListener { saveDescription() }
        }

        fun showDescription(description: String?) {
            currentDescription = description
            isEditing = false
            removeAll()

            if (description.isNullOrBlank()) {
                add(emptyDescLabel, BorderLayout.CENTER)
                val buttonRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
                    isOpaque = false
                    add(editButton)
                    add(enhanceWithAiButton)
                }
                add(buttonRow, BorderLayout.SOUTH)
            } else {
                descriptionPane.text = markdownToHtml(description)
                descriptionPane.caretPosition = 0
                add(JBScrollPane(descriptionPane).apply {
                    border = JBUI.Borders.empty()
                    preferredSize = Dimension(0, JBUI.scale(200))
                }, BorderLayout.CENTER)

                val buttonRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
                    isOpaque = false
                    add(editButton)
                    add(enhanceWithAiButton)
                }
                add(buttonRow, BorderLayout.SOUTH)
            }

            revalidate()
            repaint()
        }

        private fun enterEditMode() {
            isEditing = true
            removeAll()
            editArea.text = currentDescription ?: ""
            add(JBScrollPane(editArea).apply {
                border = JBUI.Borders.empty()
                preferredSize = Dimension(0, JBUI.scale(200))
            }, BorderLayout.CENTER)

            val buttonRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
                isOpaque = false
                add(updateButton)
                add(cancelEditButton)
            }
            add(buttonRow, BorderLayout.SOUTH)
            revalidate()
            repaint()
            editArea.requestFocusInWindow()
        }

        private fun exitEditMode() {
            showDescription(currentDescription)
        }

        private fun markdownToHtml(md: String): String {
            val textColor = StatusColors.htmlColor(SECONDARY_TEXT)
            return "<html><body style='font-family: sans-serif; color: $textColor;'>" +
                MarkdownToHtml.convertFragment(md) +
                "</body></html>"
        }

        private fun saveDescription() {
            val prId = currentPrId ?: return
            val newDescription = editArea.text
            val priorDescription = currentDescription
            updateButton.isEnabled = false

            scope.launch {
                // No `version` arg — PrActionService.updateDescription routes through
                // modifyPullRequest which fetches the fresh version itself and retries
                // once on a 409 stale-version response (PR 6 of the 2026-05-07
                // write-ops fix plan).
                val result = PrActionService.getInstance(project).updateDescription(prId, newDescription)
                invokeLater {
                    updateButton.isEnabled = true
                    when (result) {
                        is ApiResult.Success -> {
                            // Server accepted the update — commit local state
                            currentDescription = newDescription
                            showDescription(newDescription)
                        }
                        is ApiResult.Error -> {
                            // Server rejected the update — revert to the pre-edit
                            // text so the panel stays consistent with the server
                            // (mirrors saveTitleEdit revert pattern, COR-2 fix).
                            showDescription(priorDescription)
                            showNotification("Failed to update description: ${result.message}")
                        }
                    }
                }
            }
        }

        private fun enhanceWithAi() {
            log.info("[PR:AI] enhanceWithAi() called, prId=${currentPrId}, pr=${currentPr != null}")
            val prId = currentPrId
            if (prId == null) {
                log.warn("[PR:AI] currentPrId is null — aborting")
                return
            }
            val pr = currentPr
            if (pr == null) {
                log.warn("[PR:AI] currentPr is null — aborting")
                return
            }
            enhanceWithAiButton.isEnabled = false
            enhanceWithAiButton.text = "Generating..."
            log.info("[PR:AI] Button disabled, starting coroutine")

            scope.launch {
                try {
                    log.info("[PR:AI] Coroutine started, checking TextGenerationService")
                    val textGen = com.workflow.orchestrator.core.ai.TextGenerationService.getInstance()
                    log.info("[PR:AI] TextGenerationService: ${textGen?.javaClass?.simpleName ?: "NULL"}")
                    if (textGen == null) {
                        invokeLater {
                            enhanceWithAiButton.isEnabled = true
                            enhanceWithAiButton.text = "Enhance with AI"
                            enhanceWithAiButton.toolTipText = "AI service is not available — configure Sourcegraph in Settings"
                        }
                        return@launch
                    }

                    log.info("[PR:AI] Fetching diff for PR #$prId")
                    val diff = PrDetailService.getInstance(project).getDiff(prId)
                    log.info("[PR:AI] Diff result: ${if (diff == null) "NULL" else "${diff.length} chars"}")
                    if (diff.isNullOrBlank()) {
                        invokeLater {
                            enhanceWithAiButton.isEnabled = true
                            enhanceWithAiButton.text = "Enhance with AI"
                            enhanceWithAiButton.toolTipText = "Could not fetch PR diff from Bitbucket"
                        }
                        return@launch
                    }

                    val ticketId = com.workflow.orchestrator.core.settings.PluginSettings
                        .getInstance(project).state.activeTicketId.orEmpty()

                    log.info("[PR:AI] Calling generatePrDescription: diff=${diff.length} chars, ticketId=$ticketId, title=${pr.title}, from=${pr.fromRef?.displayId}, to=${pr.toRef?.displayId}")
                    // Send the full diff — the prompt builder owns smart selection within its
                    // 60K cap. Stream tokens into the edit area so the user sees progress.
                    val enhanced = textGen.generatePrDescription(
                        project = project,
                        diff = diff,
                        commitMessages = emptyList(),
                        tickets = emptyList(), // View-side AI assist; no Jira ticket binding available at this call site.
                        sourceBranch = pr.fromRef?.displayId ?: "",
                        targetBranch = pr.toRef?.displayId ?: "",
                        diffStat = ""
                    ) { partial ->
                        invokeLater {
                            // First partial flips us into edit mode and primes the area.
                            if (!editArea.isVisible || editArea.text.isBlank()) enterEditMode()
                            editArea.text = partial
                        }
                    }

                    log.info("[PR:AI] generatePrDescription result: ${if (enhanced == null) "NULL" else "${enhanced.length} chars"}")
                    invokeLater {
                        enhanceWithAiButton.isEnabled = true
                        enhanceWithAiButton.text = "Enhance with AI"
                        if (!enhanced.isNullOrBlank()) {
                            enhanceWithAiButton.toolTipText = "Use AI to generate or enhance the PR description"
                            enterEditMode()
                            editArea.text = enhanced
                            editArea.caretPosition = 0
                            log.info("[PR:AI] Description set in edit mode")
                        } else {
                            enhanceWithAiButton.toolTipText = "AI returned empty — check IDE log for errors"
                            log.warn("[PR:AI] AI returned null/empty for PR #$prId")
                        }
                    }
                } catch (e: Exception) {
                    log.warn("[PR:AI] Exception: ${e::class.simpleName}: ${e.message}", e)
                    invokeLater {
                        enhanceWithAiButton.isEnabled = true
                        enhanceWithAiButton.text = "Enhance with AI"
                        enhanceWithAiButton.toolTipText = "Failed: ${e.message}"
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------
    // Activity sub-panel
    // ---------------------------------------------------------------

    private inner class ActivitySubPanel : JPanel(BorderLayout()) {
        private val contentPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }
        private val emptyLabel = JBLabel("No activity yet.").apply {
            foreground = JBUI.CurrentTheme.Label.disabledForeground()
            horizontalAlignment = SwingConstants.CENTER
            border = JBUI.Borders.emptyTop(20)
        }

        // Comment input bar
        private val commentField = JBTextField().apply {
            emptyText.text = "Add a comment..."
        }
        private val sendCommentButton = JButton(AllIcons.Actions.Execute).apply {
            toolTipText = "Post comment"
            putClientProperty("JButton.buttonType", "borderless")
        }
        private val commentInputPanel = JPanel(BorderLayout(JBUI.scale(4), 0)).apply {
            isOpaque = false
            border = JBUI.Borders.empty(4, 8)
            add(commentField, BorderLayout.CENTER)
            add(sendCommentButton, BorderLayout.EAST)
        }

        init {
            isOpaque = false
            add(emptyLabel, BorderLayout.CENTER)
            add(commentInputPanel, BorderLayout.SOUTH)

            val submitComment = {
                val text = commentField.text.trim()
                val prId = currentPrId
                if (text.isNotBlank() && prId != null) {
                    commentField.isEnabled = false
                    sendCommentButton.isEnabled = false
                    scope.launch {
                        val result = PrActionService.getInstance(project).addComment(prId, text)
                        invokeLater {
                            when (result) {
                                is ApiResult.Success -> {
                                    commentField.text = ""
                                    commentField.isEnabled = true
                                    sendCommentButton.isEnabled = true
                                    refreshActivities()
                                }
                                is ApiResult.Error -> {
                                    commentField.isEnabled = true
                                    sendCommentButton.isEnabled = true
                                    log.warn("[PR:Activity] Comment failed: ${result.message}")
                                    WorkflowNotificationService.getInstance(project).notifyError(
                                        WorkflowNotificationService.GROUP_PR,
                                        "Comment Failed",
                                        "Failed to post comment: ${result.message}"
                                    )
                                }
                            }
                        }
                    }
                }
            }

            commentField.addActionListener { submitComment() }
            sendCommentButton.addActionListener { submitComment() }
        }

        private fun navigateToFile(relativePath: String, line: Int) {
            // Phase 5 T15 / spec §7.3 — line-anchored navigation is a live-only interaction.
            // The local document's line numbers don't match the focused PR's branch when
            // we're in ReadOnly mode, so the caret would land on the wrong line.
            if (workflowContextService.state.value.interactionMode == InteractionMode.ReadOnly) {
                val focusBranch = workflowContextService.state.value.focusPr?.fromBranch ?: "<none>"
                WorkflowNotificationService.getInstance(project).notifyInfo(
                    WorkflowNotificationService.GROUP_PR,
                    "Disabled in read-only mode",
                    "Switch to '$focusBranch' to navigate to file lines anchored on the PR's diff."
                )
                return
            }
            val basePath = project.basePath ?: return
            val resolved = java.io.File(basePath, relativePath).canonicalFile
            if (!resolved.canonicalPath.startsWith(java.io.File(basePath).canonicalPath)) {
                log.warn("[PR] Path traversal attempt blocked: $relativePath")
                return
            }
            val fullPath = resolved.path
            val vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                .findFileByPath(fullPath) ?: run {
                log.warn("[PR:Activity] File not found locally: $fullPath")
                return
            }
            invokeLater {
                val editor = com.intellij.openapi.fileEditor.FileEditorManager
                    .getInstance(project).openFile(vf, true).firstOrNull()
                if (line > 0 && editor is com.intellij.openapi.fileEditor.TextEditor) {
                    val offset = editor.editor.document.getLineStartOffset(
                        (line - 1).coerceAtMost(editor.editor.document.lineCount - 1)
                    )
                    editor.editor.caretModel.moveToOffset(offset)
                    editor.editor.scrollingModel.scrollToCaret(
                        com.intellij.openapi.editor.ScrollType.CENTER
                    )
                }
            }
        }

        private fun formatAction(action: String): String {
            return when (action.uppercase()) {
                "COMMENTED" -> "commented"
                "APPROVED" -> "approved"
                "UNAPPROVED" -> "removed approval"
                "DECLINED" -> "declined"
                "MERGED" -> "merged"
                "OPENED" -> "opened"
                "UPDATED" -> "updated"
                "RESCOPED" -> "updated source branch"
                else -> action.lowercase()
            }
        }

        fun showActivities(activities: List<BitbucketPrActivity>) {
            removeAll()
            contentPanel.removeAll()

            if (activities.isEmpty()) {
                add(emptyLabel, BorderLayout.CENTER)
            } else {
                // Separate inline comments (with anchor) from general activities
                val split = PrActivityGrouping.partition(activities)
                val inlineComments = split.inline
                val generalActivities = split.general

                // Group inline comments by file:line
                if (inlineComments.isNotEmpty()) {
                    val grouped = PrActivityGrouping.groupInlineByAnchor(inlineComments)

                    for ((anchorKey, group) in grouped) {
                        // File:line header
                        val fileName = anchorKey.path.substringAfterLast('/')
                        val lineText = if (anchorKey.line > 0) ":${anchorKey.line}" else ""
                        val headerPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
                            isOpaque = false
                            border = JBUI.Borders.empty(6, 8, 2, 8)
                            val fileLabel = JBLabel("$fileName$lineText").apply {
                                icon = AllIcons.FileTypes.Any_type
                                iconTextGap = JBUI.scale(4)
                                font = font.deriveFont(Font.BOLD, JBUI.scale(11).toFloat())
                                foreground = LINK_COLOR
                                toolTipText = "Double-click to navigate to ${anchorKey.path}$lineText"
                                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                                addMouseListener(object : MouseAdapter() {
                                    override fun mouseClicked(e: MouseEvent) {
                                        if (e.clickCount >= 1) {
                                            navigateToFile(anchorKey.path, anchorKey.line)
                                        }
                                    }
                                })
                            }
                            add(fileLabel)
                        }
                        contentPanel.add(headerPanel)

                        // Render each comment in the thread
                        for (activity in group) {
                            contentPanel.add(buildCommentRow(activity, indented = true))
                        }

                        // Thin separator between file groups
                        contentPanel.add(JSeparator(SwingConstants.HORIZONTAL).apply {
                            maximumSize = Dimension(Int.MAX_VALUE, 1)
                        })
                    }
                }

                // Render general (non-inline) activities
                for (activity in generalActivities) {
                    contentPanel.add(buildActivityRow(activity))
                }

                add(JBScrollPane(contentPanel).apply {
                    border = JBUI.Borders.empty()
                    isOpaque = false
                    viewport.isOpaque = false
                }, BorderLayout.CENTER)
            }
            // Re-add comment input panel (removeAll() at top removes it)
            add(commentInputPanel, BorderLayout.SOUTH)
            revalidate()
            repaint()
        }

        /**
         * Builds a row for a general (non-inline) activity.
         */
        private fun buildActivityRow(activity: BitbucketPrActivity): JPanel {
            val authorName = activity.comment?.author?.displayName ?: activity.user.displayName
            val commentText = activity.comment?.text ?: ""
            val timestamp = if (activity.createdDate > 0) {
                DATE_FORMAT.format(java.time.Instant.ofEpochMilli(activity.createdDate))
            } else ""

            val row = JPanel(BorderLayout()).apply {
                isOpaque = false
                border = JBUI.Borders.empty(4, 8)
            }

            val topRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
                isOpaque = false
            }
            topRow.add(JBLabel(authorName).apply {
                font = font.deriveFont(Font.BOLD, JBUI.scale(11).toFloat())
            })
            topRow.add(JBLabel(formatAction(activity.action)).apply {
                font = font.deriveFont(JBUI.scale(11).toFloat())
                foreground = SECONDARY_TEXT
            })
            topRow.add(JBLabel(timestamp).apply {
                font = font.deriveFont(JBUI.scale(10).toFloat())
                foreground = SECONDARY_TEXT
            })
            row.add(topRow, BorderLayout.NORTH)

            if (commentText.isNotBlank()) {
                val bodyPanel = JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    isOpaque = false
                    border = JBUI.Borders.emptyLeft(JBUI.scale(8))
                }
                bodyPanel.add(JBLabel(StringUtils.truncate(commentText, 150)).apply {
                    font = font.deriveFont(JBUI.scale(11).toFloat())
                    foreground = SECONDARY_TEXT
                    toolTipText = if (commentText.length > 150) commentText else null
                })

                // Reply link for comment activities
                if (activity.comment != null) {
                    bodyPanel.add(buildReplyLink(activity))
                }

                row.add(bodyPanel, BorderLayout.CENTER)
            }

            return row
        }

        /**
         * Builds a row for an inline code comment (indented under file:line header).
         */
        private fun buildCommentRow(activity: BitbucketPrActivity, indented: Boolean): JPanel {
            val authorName = activity.comment?.author?.displayName ?: activity.user.displayName
            val commentText = activity.comment?.text ?: ""
            val timestamp = if (activity.createdDate > 0) {
                DATE_FORMAT.format(java.time.Instant.ofEpochMilli(activity.createdDate))
            } else ""

            val row = JPanel(BorderLayout()).apply {
                isOpaque = false
                border = if (indented) JBUI.Borders.empty(2, 24, 2, 8) else JBUI.Borders.empty(4, 8)
            }

            val topRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
                isOpaque = false
            }
            topRow.add(JBLabel(authorName).apply {
                font = font.deriveFont(Font.BOLD, JBUI.scale(11).toFloat())
            })
            topRow.add(JBLabel(timestamp).apply {
                font = font.deriveFont(JBUI.scale(10).toFloat())
                foreground = SECONDARY_TEXT
            })
            row.add(topRow, BorderLayout.NORTH)

            val bodyPanel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                border = JBUI.Borders.emptyLeft(JBUI.scale(4))
            }

            if (commentText.isNotBlank()) {
                bodyPanel.add(JBLabel(StringUtils.truncate(commentText, 200)).apply {
                    font = font.deriveFont(JBUI.scale(11).toFloat())
                    foreground = JBColor.foreground()
                    toolTipText = if (commentText.length > 200) commentText else null
                })
            }

            // Reply link
            if (activity.comment != null) {
                bodyPanel.add(buildReplyLink(activity))
            }

            row.add(bodyPanel, BorderLayout.CENTER)
            return row
        }

        /**
         * Builds a clickable "Reply" link that expands into an inline reply input.
         */
        private fun buildReplyLink(activity: BitbucketPrActivity): JPanel {
            val rawId = activity.comment?.id ?: return JPanel().apply { isOpaque = false }
            // SEC-24: Guard against integer overflow when Long comment ID exceeds Int range
            if (rawId < Int.MIN_VALUE || rawId > Int.MAX_VALUE) return JPanel().apply { isOpaque = false }
            val commentId = rawId.toInt()
            val prId = currentPrId ?: return JPanel().apply { isOpaque = false }

            val replyContainer = JPanel(CardLayout()).apply {
                isOpaque = false
            }

            // Card 1: Reply link
            val linkPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                isOpaque = false
                border = JBUI.Borders.emptyTop(2)
            }
            val replyLink = JBLabel("Reply").apply {
                font = font.deriveFont(JBUI.scale(10).toFloat())
                foreground = LINK_COLOR
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) {
                        (replyContainer.layout as CardLayout).show(replyContainer, "input")
                    }
                })
            }
            linkPanel.add(replyLink)
            replyContainer.add(linkPanel, "link")

            // Card 2: Reply input row
            val inputPanel = JPanel(BorderLayout(JBUI.scale(4), 0)).apply {
                isOpaque = false
                border = JBUI.Borders.empty(2, 0, 2, 0)
            }
            val arrowLabel = JBLabel("\u21B3").apply {
                foreground = SECONDARY_TEXT
                font = font.deriveFont(JBUI.scale(11).toFloat())
            }
            val replyField = JBTextField().apply {
                emptyText.text = "Type a reply..."
                columns = 30
            }
            val sendButton = JButton("Send").apply {
                font = font.deriveFont(JBUI.scale(10).toFloat())
                putClientProperty("JButton.buttonType", "borderless")
                isEnabled = true
                addActionListener {
                    val text = replyField.text.trim()
                    if (text.isBlank()) return@addActionListener
                    isEnabled = false
                    replyField.isEnabled = false
                    scope.launch {
                        val result = PrActionService.getInstance(project).replyToComment(prId, commentId, text)
                        invokeLater {
                            when (result) {
                                is ApiResult.Success -> {
                                    replyField.text = ""
                                    replyField.isEnabled = true
                                    isEnabled = true
                                    (replyContainer.layout as CardLayout).show(replyContainer, "link")
                                    // Refresh activities
                                    refreshActivities()
                                }
                                is ApiResult.Error -> {
                                    replyField.isEnabled = true
                                    isEnabled = true
                                    log.warn("[PR:Activity] Reply failed: ${result.message}")
                                }
                            }
                        }
                    }
                }
            }

            val leftPanel = JPanel(BorderLayout(JBUI.scale(2), 0)).apply {
                isOpaque = false
                add(arrowLabel, BorderLayout.WEST)
                add(replyField, BorderLayout.CENTER)
            }
            inputPanel.add(leftPanel, BorderLayout.CENTER)
            inputPanel.add(sendButton, BorderLayout.EAST)
            replyContainer.add(inputPanel, "input")

            // Show link by default
            (replyContainer.layout as CardLayout).show(replyContainer, "link")
            return replyContainer
        }

        /**
         * Re-fetches activities for the current PR and refreshes the panel.
         */
        private fun refreshActivities() {
            val prId = currentPrId ?: return
            scope.launch {
                val detailService = PrDetailService.getInstance(project)
                val activities = detailService.getActivities(prId)
                invokeLater {
                    if (currentPrId == prId) {
                        showActivities(activities)
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------
    // Files sub-panel
    // ---------------------------------------------------------------

    private inner class FilesSubPanel : JPanel(BorderLayout()) {
        private val filesListModel = DefaultListModel<FileDisplayItem>()
        private val filesList = JBList(filesListModel).apply {
            cellRenderer = FileCellRenderer()
            fixedCellHeight = JBUI.scale(28)
            border = JBUI.Borders.empty()
            isOpaque = false
            toolTipText = "Double-click to view diff"
        }
        private val emptyLabel = JBLabel("No file changes.").apply {
            foreground = JBUI.CurrentTheme.Label.disabledForeground()
            horizontalAlignment = SwingConstants.CENTER
            border = JBUI.Borders.emptyTop(20)
        }
        private val fileCountLabel = JBLabel("").apply {
            font = font.deriveFont(JBUI.scale(11).toFloat())
            foreground = SECONDARY_TEXT
            border = JBUI.Borders.empty(4, 8)
        }

        /** Stored changes for diff viewer lookup by index. */
        private var currentChanges: List<BitbucketPrChange> = emptyList()

        init {
            isOpaque = false
            add(emptyLabel, BorderLayout.CENTER)

            // Double-click to open diff viewer
            filesList.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount == 2) {
                        val index = filesList.selectedIndex
                        if (index < 0 || index >= currentChanges.size) return
                        openDiffViewer(currentChanges[index])
                    }
                }
            })
        }

        fun showChanges(changes: List<BitbucketPrChange>) {
            removeAll()
            filesListModel.clear()
            currentChanges = changes

            if (changes.isEmpty()) {
                add(emptyLabel, BorderLayout.CENTER)
            } else {
                fileCountLabel.text = "${changes.size} files changed"
                add(fileCountLabel, BorderLayout.NORTH)

                for (change in changes) {
                    val filePath = change.path.toString.ifBlank { change.path.name }
                    val fileName = filePath.substringAfterLast('/')
                    val dirPath = if (filePath.contains('/')) filePath.substringBeforeLast('/') else ""
                    filesListModel.addElement(FileDisplayItem(
                        fileName = fileName,
                        dirPath = dirPath,
                        fullPath = filePath,
                        changeType = change.type
                    ))
                }
                add(JBScrollPane(filesList).apply {
                    border = JBUI.Borders.empty()
                    isOpaque = false
                    viewport.isOpaque = false
                }, BorderLayout.CENTER)
            }
            revalidate()
            repaint()
        }

        private fun openDiffViewer(change: BitbucketPrChange) {
            val filePath = change.path.toString.ifBlank { change.path.name }
            if (filePath.isBlank()) return

            scope.launch {
                val detailService = PrDetailService.getInstance(project)
                val pr = currentPr ?: return@launch

                val baseRef = pr.toRef?.latestCommit ?: ""
                val headRef = pr.fromRef?.latestCommit ?: ""

                val baseText = detailService.getFileContent(filePath, baseRef)
                val headText = detailService.getFileContent(filePath, headRef)

                withContext(Dispatchers.EDT) {
                    val diffContentFactory = DiffContentFactory.getInstance()
                    val baseContent = diffContentFactory.create(project, baseText)
                    val headContent = diffContentFactory.create(project, headText)

                    val request = SimpleDiffRequest(
                        filePath,
                        baseContent,
                        headContent,
                        "Base (${pr.toRef?.displayId ?: "target"})",
                        "Changes (${pr.fromRef?.displayId ?: "source"})"
                    )

                    DiffManager.getInstance().showDiff(project, request)
                }
            }
        }
    }

    private data class FileDisplayItem(
        val fileName: String,
        val dirPath: String,
        val fullPath: String,
        val changeType: String
    )

    private class FileCellRenderer : ListCellRenderer<FileDisplayItem> {
        // Cached components — reused on every render call
        private val rootPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(2, 8)
        }
        private val leftRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
            isOpaque = false
        }
        private val badgeLabel = JBLabel().apply {
            font = font.deriveFont(Font.BOLD, JBUI.scale(10).toFloat())
            border = JBUI.Borders.empty(0, 2, 0, 4)
        }
        private val fileNameLabel = JBLabel().apply {
            font = font.deriveFont(JBUI.scale(12).toFloat())
        }
        private val dirPathLabel = JBLabel().apply {
            font = font.deriveFont(JBUI.scale(10).toFloat())
            foreground = SECONDARY_TEXT
        }
        private val diffIconLabel = JBLabel(AllIcons.Actions.Diff).apply {
            toolTipText = "Double-click to view diff"
        }

        init {
            rootPanel.add(leftRow, BorderLayout.CENTER)
            rootPanel.add(diffIconLabel, BorderLayout.EAST)
        }

        override fun getListCellRendererComponent(
            list: JList<out FileDisplayItem>,
            value: FileDisplayItem,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            rootPanel.isOpaque = isSelected
            if (isSelected) {
                rootPanel.background = UIManager.getColor("List.selectionBackground")
            }

            leftRow.removeAll()

            // Change type badge
            val letter = when (value.changeType.uppercase()) {
                "ADD" -> "A"
                "MODIFY" -> "M"
                "DELETE" -> "D"
                "RENAME" -> "R"
                "COPY" -> "C"
                else -> "?"
            }
            val color = when (value.changeType.uppercase()) {
                "ADD" -> StatusColors.SUCCESS
                "MODIFY" -> StatusColors.LINK
                "DELETE" -> StatusColors.ERROR
                "RENAME" -> StatusColors.WARNING
                else -> SECONDARY_TEXT
            }
            badgeLabel.text = letter
            badgeLabel.foreground = color
            leftRow.add(badgeLabel)

            fileNameLabel.text = value.fileName
            fileNameLabel.foreground = JBColor.foreground()
            leftRow.add(fileNameLabel)

            if (value.dirPath.isNotBlank()) {
                dirPathLabel.text = value.dirPath
                leftRow.add(dirPathLabel)
            }

            return rootPanel
        }
    }

    // ---------------------------------------------------------------
    // Commits sub-panel
    // ---------------------------------------------------------------

    private inner class CommitsSubPanel : JPanel(BorderLayout()) {
        private val commitListModel = DefaultListModel<BitbucketCommit>()
        private val commitList = JBList(commitListModel).apply {
            cellRenderer = CommitCellRenderer()
            border = JBUI.Borders.empty()
            isOpaque = false
        }
        private var lastLoadedPrId: Int? = null

        init {
            isOpaque = false
            commitList.emptyText.text = "No commits."
            add(JBScrollPane(commitList).apply {
                border = JBUI.Borders.empty()
                isOpaque = false
                viewport.isOpaque = false
            }, BorderLayout.CENTER)
        }

        fun showCommits(prId: Int) {
            if (prId == lastLoadedPrId && commitListModel.size() > 0) return
            lastLoadedPrId = prId
            scope.launch {
                val commits = PrDetailService.getInstance(project).getCommits(prId)
                withContext(Dispatchers.EDT) {
                    commitListModel.clear()
                    commits.forEach { commitListModel.addElement(it) }
                }
            }
        }
    }

    private class CommitCellRenderer : ListCellRenderer<BitbucketCommit> {
        private val rootPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4, 8)
        }
        private val hashLabel = JBLabel().apply {
            font = Font(Font.MONOSPACED, Font.PLAIN, JBUI.scale(12))
            foreground = StatusColors.LINK
        }
        private val messageLabel = JBLabel().apply {
            font = font.deriveFont(JBUI.scale(12).toFloat())
        }
        private val authorLabel = JBLabel().apply {
            font = font.deriveFont(JBUI.scale(11).toFloat())
            foreground = StatusColors.SECONDARY_TEXT
        }
        private val timeLabel = JBLabel().apply {
            font = font.deriveFont(JBUI.scale(11).toFloat())
            foreground = StatusColors.SECONDARY_TEXT
        }
        private val leftPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
            isOpaque = false
        }
        private val rightPanel = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), 0)).apply {
            isOpaque = false
        }

        init {
            leftPanel.add(hashLabel)
            leftPanel.add(messageLabel)
            rightPanel.add(authorLabel)
            rightPanel.add(timeLabel)
            rootPanel.add(leftPanel, BorderLayout.WEST)
            rootPanel.add(rightPanel, BorderLayout.EAST)
        }

        override fun getListCellRendererComponent(
            list: JList<out BitbucketCommit>,
            value: BitbucketCommit,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            hashLabel.text = value.displayId
            val firstLine = value.message.lineSequence().firstOrNull()?.take(80) ?: ""
            messageLabel.text = firstLine
            authorLabel.text = value.author?.displayName?.ifBlank { value.author?.name } ?: ""
            timeLabel.text = TimeFormatter.relative(value.authorTimestamp)

            rootPanel.background = if (isSelected) list.selectionBackground else list.background
            rootPanel.isOpaque = true
            return rootPanel
        }
    }

}

/**
 * Dialog for merge options: strategy selection, delete source branch, and commit message.
 */
private class MergeOptionsDialog(
    project: Project,
    private val prId: Int,
    private val strategies: List<BitbucketMergeStrategy>,
    private val mergeStatus: BitbucketMergeStatus?
) : DialogWrapper(project, false) {

    private val strategyCombo = ComboBox<BitbucketMergeStrategy>()
    private val deleteSourceCheckbox = JCheckBox("Delete source branch after merge")
    private val commitMessageArea = JBTextArea(4, 50).apply {
        lineWrap = true
        wrapStyleWord = true
        border = JBUI.Borders.empty(4)
    }

    val selectedStrategyId: String?
        get() = (strategyCombo.selectedItem as? BitbucketMergeStrategy)?.id

    val deleteSourceBranch: Boolean
        get() = deleteSourceCheckbox.isSelected

    val commitMessage: String
        get() = commitMessageArea.text.trim()

    init {
        title = "Merge PR #$prId"
        setOKButtonText("Merge")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(8, 12)
        }

        // Veto warnings (if any)
        if (mergeStatus != null && mergeStatus.vetoes.isNotEmpty()) {
            val warningPanel = JPanel(BorderLayout()).apply {
                isOpaque = false
                border = JBUI.Borders.emptyBottom(8)
                alignmentX = Component.LEFT_ALIGNMENT
                maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(60))
            }
            val warningIcon = JBLabel(AllIcons.General.Warning)
            // Escape summaryMessage before inserting into HTML to prevent Swing HTML injection
            val vetoText = mergeStatus.vetoes.joinToString("\n") { it.summaryMessage }
            val warningLabel = JBLabel("<html><b>Warnings:</b><br>${HtmlEscape.escapeHtml(vetoText).replace("\n", "<br>")}</html>").apply {
                foreground = StatusColors.WARNING
            }
            warningPanel.add(warningIcon, BorderLayout.WEST)
            warningPanel.add(warningLabel, BorderLayout.CENTER)
            panel.add(warningPanel)
        }

        // Conflict warning
        if (mergeStatus?.conflicted == true) {
            val conflictLabel = JBLabel("<html><b>Conflicts detected</b> — this merge may require conflict resolution.</html>").apply {
                icon = AllIcons.General.Warning
                foreground = StatusColors.ERROR
                border = JBUI.Borders.emptyBottom(8)
                alignmentX = Component.LEFT_ALIGNMENT
            }
            panel.add(conflictLabel)
        }

        // Merge strategy
        if (strategies.isNotEmpty()) {
            val strategyLabel = JBLabel("Merge strategy:").apply {
                border = JBUI.Borders.emptyBottom(4)
                alignmentX = Component.LEFT_ALIGNMENT
            }
            panel.add(strategyLabel)

            for (strategy in strategies) {
                strategyCombo.addItem(strategy)
            }
            strategyCombo.renderer = object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean
                ): Component {
                    super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                    if (value is BitbucketMergeStrategy) {
                        text = value.name.ifBlank { value.id }
                        toolTipText = value.description.ifBlank { null }
                    }
                    return this
                }
            }
            strategyCombo.alignmentX = Component.LEFT_ALIGNMENT
            strategyCombo.maximumSize = Dimension(Int.MAX_VALUE, strategyCombo.preferredSize.height)
            panel.add(strategyCombo)
            panel.add(Box.createVerticalStrut(JBUI.scale(8)))
        }

        // Delete source branch checkbox
        deleteSourceCheckbox.alignmentX = Component.LEFT_ALIGNMENT
        panel.add(deleteSourceCheckbox)
        panel.add(Box.createVerticalStrut(JBUI.scale(8)))

        // Commit message
        val commitLabel = JBLabel("Merge commit message (optional):").apply {
            border = JBUI.Borders.emptyBottom(4)
            alignmentX = Component.LEFT_ALIGNMENT
        }
        panel.add(commitLabel)

        val scrollPane = JBScrollPane(commitMessageArea).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            preferredSize = Dimension(JBUI.scale(400), JBUI.scale(80))
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(120))
        }
        panel.add(scrollPane)

        return panel
    }
}

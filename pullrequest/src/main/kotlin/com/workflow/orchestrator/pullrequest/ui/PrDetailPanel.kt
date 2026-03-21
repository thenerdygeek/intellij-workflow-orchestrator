package com.workflow.orchestrator.pullrequest.ui

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
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
import com.workflow.orchestrator.core.bitbucket.BitbucketPrActivity
import com.workflow.orchestrator.core.bitbucket.BitbucketPrChange
import com.workflow.orchestrator.core.bitbucket.BitbucketPrDetail
import com.workflow.orchestrator.core.bitbucket.BitbucketPrRef
import com.workflow.orchestrator.core.ui.TimeFormatter
import com.workflow.orchestrator.core.settings.ConnectionSettings
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.auth.CredentialStore
import com.workflow.orchestrator.core.bitbucket.BitbucketBranchClient
import com.workflow.orchestrator.core.bitbucket.BitbucketReviewer
import com.workflow.orchestrator.core.bitbucket.BitbucketReviewerUser
import com.workflow.orchestrator.core.bitbucket.BitbucketUser
import com.workflow.orchestrator.core.events.EventBus
import com.workflow.orchestrator.core.events.WorkflowEvent
import com.workflow.orchestrator.core.model.ServiceType
import com.workflow.orchestrator.pullrequest.service.PrActionService
import com.workflow.orchestrator.pullrequest.service.PrDetailService
import com.workflow.orchestrator.pullrequest.service.PrListService
import git4idea.repo.GitRepositoryManager
import kotlinx.coroutines.*
import java.awt.*
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import java.util.*
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

    private var currentPrId: Int? = null
    private var currentPr: BitbucketPrDetail? = null
    private var currentMergeStatus: BitbucketMergeStatus? = null
    private var loadJob: Job? = null

    // Card names
    private companion object {
        const val CARD_EMPTY = "empty"
        const val CARD_LOADING = "loading"
        const val CARD_DETAIL = "detail"
        const val CARD_CREATE = "create"

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

    // -- Create PR form components --
    private val createPanel = JPanel(BorderLayout()).apply {
        isOpaque = false
    }
    private val createSourceBranchLabel = JBLabel("").apply {
        font = font.deriveFont(JBUI.scale(12).toFloat())
    }
    private val createTargetBranchCombo = ComboBox<String>().apply {
        isEditable = false
    }
    private val createTitleField = JBTextField().apply {
        emptyText.text = "PR title"
    }
    private val createDescriptionArea = JBTextArea().apply {
        lineWrap = true
        wrapStyleWord = true
        rows = 6
        font = font.deriveFont(JBUI.scale(12).toFloat())
        border = JBUI.Borders.empty(8)
    }
    private val createReviewersPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
        isOpaque = false
    }
    private val createAddReviewerLink = JBLabel("+ Add").apply {
        foreground = LINK_COLOR
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        font = font.deriveFont(JBUI.scale(11).toFloat())
    }
    private val createButton = JButton("Create Pull Request").apply {
        icon = AllIcons.General.Add
    }
    private val createBackLabel = JBLabel("\u2190 Back to list").apply {
        foreground = LINK_COLOR
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        font = font.deriveFont(JBUI.scale(11).toFloat())
        border = JBUI.Borders.emptyBottom(4)
    }
    private val selectedReviewerUsernames = mutableListOf<String>()
    private val selectedReviewerDisplayNames = mutableListOf<String>()

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
        font = font.deriveFont(JBUI.scale(12).toFloat())
        foreground = SECONDARY_TEXT
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
    private val aiReviewSubPanel = AiReviewSubPanel()

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

        buildCreatePanel()
        add(createPanel, CARD_CREATE)

        showEmpty()
    }

    // ---------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------

    fun showEmpty() {
        currentPrId = null
        currentPr = null
        (layout as CardLayout).show(this, CARD_EMPTY)
    }

    fun showPr(prId: Int) {
        if (prId == currentPrId) return
        currentPrId = prId
        currentPr = null
        loadJob?.cancel()

        (layout as CardLayout).show(this, CARD_LOADING)

        loadJob = scope.launch {
            val detailService = PrDetailService.getInstance(project)
            val prDetail = detailService.getDetail(prId)

            if (prDetail == null) {
                SwingUtilities.invokeLater {
                    if (currentPrId != prId) return@invokeLater
                    showEmpty()
                }
                return@launch
            }

            SwingUtilities.invokeLater {
                if (currentPrId != prId) return@invokeLater
                currentPr = prDetail
                renderPrHeader(prId, prDetail.title, prDetail.state,
                    prDetail.fromRef, prDetail.toRef)
                renderReviewers(prDetail)
                descriptionSubPanel.showDescription(prDetail.description)
                selectToggle(descriptionToggle)
                (layout as CardLayout).show(this@PrDetailPanel, CARD_DETAIL)
            }

            // Load activities in background
            val activities = detailService.getActivities(prId)
            SwingUtilities.invokeLater {
                if (currentPrId != prId) return@invokeLater
                activitySubPanel.showActivities(activities)
            }

            // Load changes in background
            val changes = detailService.getChanges(prId)
            SwingUtilities.invokeLater {
                if (currentPrId != prId) return@invokeLater
                filesSubPanel.showChanges(changes)
            }

            // Check merge preconditions in background
            val mergeStatus = PrActionService.getInstance(project).checkMergeStatus(prId)
            SwingUtilities.invokeLater {
                if (currentPrId != prId) return@invokeLater
                currentMergeStatus = mergeStatus
                updateMergeButtonState(mergeStatus)
            }

            // Fetch build status for the source branch's latest commit
            val commitId = prDetail.fromRef?.latestCommit
            if (!commitId.isNullOrBlank()) {
                val statuses = detailService.getBuildStatus(commitId)
                SwingUtilities.invokeLater {
                    if (currentPrId != prId) return@invokeLater
                    updateBuildStatusBadge(statuses)
                }
            }
        }
    }

    /**
     * Show a PR directly from a BitbucketPrDetail object (avoids re-fetch).
     */
    fun showPrDetail(pr: BitbucketPrDetail) {
        currentPrId = pr.id
        currentPr = pr
        loadJob?.cancel()

        SwingUtilities.invokeLater {
            renderPrHeader(pr.id, pr.title, pr.state, pr.fromRef, pr.toRef)
            renderReviewers(pr)
            descriptionSubPanel.showDescription(pr.description)
            selectToggle(descriptionToggle)
            (layout as CardLayout).show(this@PrDetailPanel, CARD_DETAIL)
        }

        // Load activities and changes in background
        loadJob = scope.launch {
            val detailService = PrDetailService.getInstance(project)

            val activities = detailService.getActivities(pr.id)
            SwingUtilities.invokeLater {
                if (currentPrId != pr.id) return@invokeLater
                activitySubPanel.showActivities(activities)
            }

            val changes = detailService.getChanges(pr.id)
            SwingUtilities.invokeLater {
                if (currentPrId != pr.id) return@invokeLater
                filesSubPanel.showChanges(changes)
            }

            // Check merge preconditions in background
            val mergeStatus = PrActionService.getInstance(project).checkMergeStatus(pr.id)
            SwingUtilities.invokeLater {
                if (currentPrId != pr.id) return@invokeLater
                currentMergeStatus = mergeStatus
                updateMergeButtonState(mergeStatus)
            }

            // Fetch build status for the source branch's latest commit
            val commitId = pr.fromRef?.latestCommit
            if (!commitId.isNullOrBlank()) {
                val statuses = detailService.getBuildStatus(commitId)
                SwingUtilities.invokeLater {
                    if (currentPrId != pr.id) return@invokeLater
                    updateBuildStatusBadge(statuses)
                }
            }
        }
    }

    // ---------------------------------------------------------------
    // Create PR form
    // ---------------------------------------------------------------

    fun showCreateForm() {
        currentPrId = null
        currentPr = null
        loadJob?.cancel()

        // Get current git branch
        val currentBranch = GitRepositoryManager.getInstance(project)
            .repositories.firstOrNull()?.currentBranch?.name ?: "unknown"
        createSourceBranchLabel.text = currentBranch

        // Auto-fill title from branch name (e.g., "PROJ-123-feature" -> "PROJ-123: ")
        val ticketPattern = Regex("^([A-Z]+-\\d+)")
        val match = ticketPattern.find(currentBranch)
        createTitleField.text = if (match != null) "${match.groupValues[1]}: " else ""

        // Clear previous form state
        createDescriptionArea.text = ""
        selectedReviewerUsernames.clear()
        selectedReviewerDisplayNames.clear()
        renderCreateReviewers()
        createButton.isEnabled = true
        createButton.text = "Create Pull Request"

        // Populate target branches
        createTargetBranchCombo.removeAllItems()
        val settings = PluginSettings.getInstance(project).state
        val defaultTarget = settings.defaultTargetBranch?.ifBlank { "develop" } ?: "develop"
        createTargetBranchCombo.addItem(defaultTarget)

        (layout as CardLayout).show(this, CARD_CREATE)

        // Load branches from Bitbucket in background
        scope.launch {
            val connSettings = ConnectionSettings.getInstance().state
            val url = connSettings.bitbucketUrl.trimEnd('/')
            if (url.isBlank()) return@launch
            val projectKey = settings.bitbucketProjectKey.orEmpty()
            val repoSlug = settings.bitbucketRepoSlug.orEmpty()
            if (projectKey.isBlank() || repoSlug.isBlank()) return@launch

            val credentialStore = CredentialStore()
            val client = BitbucketBranchClient(
                baseUrl = url,
                tokenProvider = { credentialStore.getToken(ServiceType.BITBUCKET) }
            )
            when (val result = client.getBranches(projectKey, repoSlug)) {
                is ApiResult.Success -> {
                    SwingUtilities.invokeLater {
                        val branches = result.data.map { it.displayId }
                        createTargetBranchCombo.removeAllItems()
                        // Put default target first if it exists
                        if (branches.contains(defaultTarget)) {
                            createTargetBranchCombo.addItem(defaultTarget)
                        }
                        for (branch in branches) {
                            if (branch != defaultTarget && branch != currentBranch) {
                                createTargetBranchCombo.addItem(branch)
                            }
                        }
                    }
                }
                is ApiResult.Error -> { /* keep default */ }
            }
        }
    }

    private fun buildCreatePanel() {
        val contentPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(12, 16)
        }

        // Back navigation
        val backRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(20))
            alignmentX = Component.LEFT_ALIGNMENT
        }
        createBackLabel.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                onBackClicked?.invoke()
            }
        })
        backRow.add(createBackLabel)
        contentPanel.add(backRow)

        // Title heading
        contentPanel.add(JBLabel("Create Pull Request").apply {
            font = font.deriveFont(Font.BOLD, JBUI.scale(16).toFloat())
            foreground = JBColor.foreground()
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.empty(8, 0)
        })

        // Source branch (read-only)
        val sourceRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
            isOpaque = false
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(28))
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.emptyBottom(4)
        }
        sourceRow.add(JBLabel("Source:").apply {
            font = font.deriveFont(Font.BOLD, JBUI.scale(12).toFloat())
            foreground = SECONDARY_TEXT
        })
        sourceRow.add(createSourceBranchLabel)
        contentPanel.add(sourceRow)

        // Target branch
        val targetRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
            isOpaque = false
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(32))
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.emptyBottom(4)
        }
        targetRow.add(JBLabel("Target:").apply {
            font = font.deriveFont(Font.BOLD, JBUI.scale(12).toFloat())
            foreground = SECONDARY_TEXT
        })
        createTargetBranchCombo.preferredSize = Dimension(JBUI.scale(200), JBUI.scale(24))
        targetRow.add(createTargetBranchCombo)
        contentPanel.add(targetRow)

        // Title field
        val titleRow = JPanel(BorderLayout()).apply {
            isOpaque = false
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(40))
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.emptyBottom(4)
        }
        titleRow.add(JBLabel("Title:").apply {
            font = font.deriveFont(Font.BOLD, JBUI.scale(12).toFloat())
            foreground = SECONDARY_TEXT
            border = JBUI.Borders.emptyRight(8)
        }, BorderLayout.WEST)
        titleRow.add(createTitleField, BorderLayout.CENTER)
        contentPanel.add(titleRow)

        // Description area
        contentPanel.add(JBLabel("Description:").apply {
            font = font.deriveFont(Font.BOLD, JBUI.scale(12).toFloat())
            foreground = SECONDARY_TEXT
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.emptyBottom(4)
        })
        val descScroll = JBScrollPane(createDescriptionArea).apply {
            border = JBUI.Borders.empty()
            preferredSize = Dimension(0, JBUI.scale(120))
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(200))
            alignmentX = Component.LEFT_ALIGNMENT
        }
        contentPanel.add(descScroll)

        // Reviewers row
        val reviewersRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
            isOpaque = false
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(28))
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.empty(8, 0, 4, 0)
        }
        reviewersRow.add(JBLabel("Reviewers:").apply {
            font = font.deriveFont(Font.BOLD, JBUI.scale(12).toFloat())
            foreground = SECONDARY_TEXT
        })
        reviewersRow.add(createReviewersPanel)
        createAddReviewerLink.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                showCreateReviewerPopup(createAddReviewerLink)
            }
        })
        reviewersRow.add(createAddReviewerLink)
        contentPanel.add(reviewersRow)

        // Create button
        val buttonRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(40))
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.emptyTop(8)
        }
        createButton.addActionListener { submitCreatePr() }
        buttonRow.add(createButton)
        contentPanel.add(buttonRow)

        val scrollPane = JBScrollPane(contentPanel).apply {
            border = JBUI.Borders.empty()
            isOpaque = false
            viewport.isOpaque = false
        }
        createPanel.add(scrollPane, BorderLayout.CENTER)
    }

    private fun submitCreatePr() {
        val title = createTitleField.text.trim()
        if (title.isBlank()) {
            showNotification("PR title cannot be empty")
            return
        }
        val fromBranch = createSourceBranchLabel.text
        val toBranch = createTargetBranchCombo.selectedItem as? String ?: return
        val description = createDescriptionArea.text.trim()

        createButton.isEnabled = false
        createButton.text = "Creating..."

        val settings = PluginSettings.getInstance(project).state
        val connSettings = ConnectionSettings.getInstance().state
        val url = connSettings.bitbucketUrl.trimEnd('/')
        val projectKey = settings.bitbucketProjectKey.orEmpty()
        val repoSlug = settings.bitbucketRepoSlug.orEmpty()

        if (url.isBlank() || projectKey.isBlank() || repoSlug.isBlank()) {
            showNotification("Bitbucket connection not configured. Check Settings > Tools > Workflow Orchestrator.")
            createButton.isEnabled = true
            createButton.text = "Create Pull Request"
            return
        }

        val reviewers = selectedReviewerUsernames.map { BitbucketReviewer(BitbucketReviewerUser(it)) }
            .takeIf { it.isNotEmpty() }

        scope.launch {
            val credentialStore = CredentialStore()
            val client = BitbucketBranchClient(
                baseUrl = url,
                tokenProvider = { credentialStore.getToken(ServiceType.BITBUCKET) }
            )
            when (val result = client.createPullRequest(
                projectKey = projectKey,
                repoSlug = repoSlug,
                title = title,
                description = description,
                fromBranch = fromBranch,
                toBranch = toBranch,
                reviewers = reviewers
            )) {
                is ApiResult.Success -> {
                    val pr = result.data
                    val prUrl = pr.links.self.firstOrNull()?.href ?: ""
                    // Extract ticket ID from branch name
                    val ticketPattern = Regex("^([A-Z]+-\\d+)")
                    val ticketId = ticketPattern.find(fromBranch)?.groupValues?.get(1) ?: ""

                    // Emit PullRequestCreated event
                    project.getService(EventBus::class.java)
                        .emit(WorkflowEvent.PullRequestCreated(prUrl, pr.id, ticketId))

                    // Refresh PR list
                    PrListService.getInstance(project).refresh()

                    SwingUtilities.invokeLater {
                        createButton.isEnabled = true
                        createButton.text = "Create Pull Request"
                        // Show the newly created PR by loading it
                        showPr(pr.id)
                        com.intellij.notification.NotificationGroupManager.getInstance()
                            .getNotificationGroup("workflow.build")
                            .createNotification(
                                "PR #${pr.id} created successfully",
                                com.intellij.notification.NotificationType.INFORMATION
                            )
                            .notify(project)
                    }
                }
                is ApiResult.Error -> {
                    SwingUtilities.invokeLater {
                        createButton.isEnabled = true
                        createButton.text = "Create Pull Request"
                        showNotification("Failed to create PR: ${result.message}")
                    }
                }
            }
        }
    }

    private fun renderCreateReviewers() {
        createReviewersPanel.removeAll()
        for (i in selectedReviewerUsernames.indices) {
            val name = selectedReviewerDisplayNames.getOrElse(i) { selectedReviewerUsernames[i] }
            val chipPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(2), 0)).apply {
                isOpaque = false
            }
            chipPanel.add(JBLabel(name).apply {
                font = font.deriveFont(JBUI.scale(11).toFloat())
                foreground = JBColor.foreground()
            })
            val removeLabel = JBLabel("\u00D7").apply {
                foreground = StatusColors.ERROR
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                font = font.deriveFont(Font.BOLD, JBUI.scale(11).toFloat())
                toolTipText = "Remove $name"
            }
            val idx = i
            removeLabel.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    selectedReviewerUsernames.removeAt(idx)
                    selectedReviewerDisplayNames.removeAt(idx)
                    renderCreateReviewers()
                }
            })
            chipPanel.add(removeLabel)
            createReviewersPanel.add(chipPanel)
        }
        createReviewersPanel.revalidate()
        createReviewersPanel.repaint()
    }

    private fun showCreateReviewerPopup(relativeTo: Component) {
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
                    val url = ConnectionSettings.getInstance().state.bitbucketUrl.trimEnd('/')
                    if (url.isBlank()) return@launch
                    val credentialStore = CredentialStore()
                    val client = BitbucketBranchClient(
                        baseUrl = url,
                        tokenProvider = { credentialStore.getToken(ServiceType.BITBUCKET) }
                    )
                    when (val result = client.getUsers(query)) {
                        is ApiResult.Success -> {
                            SwingUtilities.invokeLater {
                                userListModel.clear()
                                result.data
                                    .filter { it.name !in selectedReviewerUsernames }
                                    .forEach { userListModel.addElement(it) }
                            }
                        }
                        is ApiResult.Error -> { /* ignore search errors */ }
                    }
                }
            }
        })

        // Click to add reviewer
        userList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 1) {
                    val selected = userList.selectedValue ?: return
                    popup.cancel()
                    selectedReviewerUsernames.add(selected.name)
                    selectedReviewerDisplayNames.add(selected.displayName.ifBlank { selected.name })
                    renderCreateReviewers()
                }
            }
        })

        popup.showUnderneathOf(relativeTo)
    }

    override fun dispose() {
        loadJob?.cancel()
        scope.cancel()
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
                if (e.clickCount == 2 && currentPr?.state.equals("OPEN", ignoreCase = true)) {
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
        toggleGroup.add(aiReviewToggle)
        toggleRow.add(descriptionToggle)
        toggleRow.add(activityToggle)
        toggleRow.add(filesToggle)
        toggleRow.add(commitsToggle)
        toggleRow.add(aiReviewToggle)
        contentPanel.add(toggleRow)

        // Content cards
        contentCards.add(descriptionSubPanel, "description")
        contentCards.add(activitySubPanel, "activity")
        contentCards.add(filesSubPanel, "files")
        contentCards.add(commitsSubPanel, "commits")
        contentCards.add(aiReviewSubPanel, "aiReview")
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
        aiReviewToggle.addActionListener { (contentCards.layout as CardLayout).show(contentCards, "aiReview") }

        val scrollPane = JBScrollPane(contentPanel).apply {
            border = JBUI.Borders.empty()
            isOpaque = false
            viewport.isOpaque = false
        }
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
                        SwingUtilities.invokeLater {
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
                        SwingUtilities.invokeLater {
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
                    SwingUtilities.invokeLater {
                        com.intellij.notification.NotificationGroupManager.getInstance()
                            .getNotificationGroup("workflow.build")
                            .createNotification("PR action failed: ${e.message}", com.intellij.notification.NotificationType.ERROR)
                            .notify(project)
                    }
                }
            }
        }

        mergeButton.addActionListener {
            val prId = currentPrId ?: return@addActionListener
            val version = currentPr?.version ?: 0
            val mergeStatus = currentMergeStatus

            // Show merge options dialog
            scope.launch {
                val strategies = PrActionService.getInstance(project).getMergeStrategies()
                SwingUtilities.invokeLater {
                    val dialog = MergeOptionsDialog(
                        project = project,
                        prId = prId,
                        strategies = strategies,
                        mergeStatus = mergeStatus
                    )
                    if (!dialog.showAndGet()) return@invokeLater

                    scope.launch {
                        try {
                            PrActionService.getInstance(project).merge(
                                prId = prId,
                                version = version,
                                strategyId = dialog.selectedStrategyId,
                                deleteSourceBranch = dialog.deleteSourceBranch,
                                commitMessage = dialog.commitMessage.takeIf { it.isNotBlank() }
                            )
                            SwingUtilities.invokeLater {
                                mergeButton.isEnabled = false
                                approveButton.isEnabled = false
                                needsWorkButton.isEnabled = false
                                declineButton.isEnabled = false
                            }
                        } catch (e: Exception) {
                            SwingUtilities.invokeLater {
                                com.intellij.notification.NotificationGroupManager.getInstance()
                                    .getNotificationGroup("workflow.build")
                                    .createNotification("PR action failed: ${e.message}", com.intellij.notification.NotificationType.ERROR)
                                    .notify(project)
                            }
                        }
                    }
                }
            }
        }

        declineButton.addActionListener {
            val prId = currentPrId ?: return@addActionListener
            val version = currentPr?.version ?: 0
            val confirm = com.intellij.openapi.ui.Messages.showYesNoDialog(
                this,
                "Decline PR #$prId?",
                "Confirm Decline",
                com.intellij.openapi.ui.Messages.getWarningIcon()
            )
            if (confirm != com.intellij.openapi.ui.Messages.YES) return@addActionListener
            scope.launch {
                try {
                    PrActionService.getInstance(project).decline(prId, version)
                    SwingUtilities.invokeLater {
                        mergeButton.isEnabled = false
                        approveButton.isEnabled = false
                        needsWorkButton.isEnabled = false
                        declineButton.isEnabled = false
                    }
                } catch (e: Exception) {
                    SwingUtilities.invokeLater {
                        com.intellij.notification.NotificationGroupManager.getInstance()
                            .getNotificationGroup("workflow.build")
                            .createNotification("PR action failed: ${e.message}", com.intellij.notification.NotificationType.ERROR)
                            .notify(project)
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
                    SwingUtilities.invokeLater {
                        showNotification("Cannot determine current user for Needs Work")
                    }
                    return@launch
                }
                try {
                    val result = PrActionService.getInstance(project).setNeedsWork(prId, currentUser)
                    SwingUtilities.invokeLater {
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
                    SwingUtilities.invokeLater {
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
            val settings = PluginSettings.getInstance(project).state
            val projectKey = settings.bitbucketProjectKey.orEmpty()
            val repoSlug = settings.bitbucketRepoSlug.orEmpty()
            if (bitbucketUrl.isNotBlank() && projectKey.isNotBlank() && repoSlug.isNotBlank()) {
                BrowserUtil.browse("$bitbucketUrl/projects/$projectKey/repos/$repoSlug/pull-requests/$prId")
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
            aiReviewToggle -> "aiReview"
            else -> "description"
        }
        (contentCards.layout as CardLayout).show(contentCards, cardName)
    }

    // ---------------------------------------------------------------
    // Merge status helpers
    // ---------------------------------------------------------------

    private fun updateMergeButtonState(mergeStatus: BitbucketMergeStatus?) {
        if (mergeStatus == null) {
            // Could not fetch status — leave button in its current state
            mergeButton.toolTipText = "Merge status unknown"
            return
        }

        if (mergeStatus.conflicted) {
            mergeButton.icon = AllIcons.General.Warning
        } else {
            mergeButton.icon = AllIcons.Vcs.Merge
        }

        if (mergeStatus.canMerge) {
            // mergeButton.isEnabled is already set by renderPrHeader based on PR state
            mergeButton.toolTipText = if (mergeStatus.conflicted) {
                "Merge (conflicts detected — may require resolution)"
            } else {
                "Merge this pull request"
            }
        } else {
            mergeButton.isEnabled = false
            val vetoReasons = mergeStatus.vetoes.joinToString("\n") { it.summaryMessage }
            mergeButton.toolTipText = if (vetoReasons.isNotBlank()) {
                "Cannot merge:\n$vetoReasons"
            } else {
                "Cannot merge — preconditions not met"
            }
        }
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
        val isOpen = state.equals("OPEN", ignoreCase = true)
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
                SwingUtilities.invokeLater {
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
     */
    private var cachedUsername: String? = null

    private suspend fun resolveCurrentUsername(): String? {
        cachedUsername?.let { return it }
        val url = ConnectionSettings.getInstance().state.bitbucketUrl.trimEnd('/')
        if (url.isBlank()) return null
        val credentialStore = CredentialStore()
        val client = BitbucketBranchClient(
            baseUrl = url,
            tokenProvider = { credentialStore.getToken(ServiceType.BITBUCKET) }
        )
        return when (val result = client.getCurrentUsername()) {
            is ApiResult.Success -> { cachedUsername = result.data; result.data }
            is ApiResult.Error -> null
        }
    }

    private fun renderReviewers(pr: BitbucketPrDetail) {
        reviewersPanel.removeAll()
        val isOpen = pr.state.equals("OPEN", ignoreCase = true)

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
                            SwingUtilities.invokeLater {
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
            SwingUtilities.invokeLater {
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
                SwingUtilities.invokeLater {
                    if (currentPrId != prId) return@invokeLater
                    currentPr = prDetail
                    renderReviewers(prDetail)
                }
            }
        }
    }

    private fun showNotification(message: String) {
        com.intellij.notification.NotificationGroupManager.getInstance()
            .getNotificationGroup("workflow.build")
            .createNotification(message, com.intellij.notification.NotificationType.ERROR)
            .notify(project)
    }

    /**
     * Shows a popup with user search for adding reviewers.
     * Debounces input by 300ms, queries BitbucketBranchClient.getUsers().
     */
    private fun showAddReviewerPopup(relativeTo: Component) {
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
                    val url = ConnectionSettings.getInstance().state.bitbucketUrl.trimEnd('/')
                    if (url.isBlank()) return@launch
                    val credentialStore = CredentialStore()
                    val client = BitbucketBranchClient(
                        baseUrl = url,
                        tokenProvider = { credentialStore.getToken(ServiceType.BITBUCKET) }
                    )
                    when (val result = client.getUsers(query)) {
                        is ApiResult.Success -> {
                            SwingUtilities.invokeLater {
                                userListModel.clear()
                                result.data.forEach { userListModel.addElement(it) }
                            }
                        }
                        is ApiResult.Error -> { /* ignore search errors */ }
                    }
                }
            }
        })

        // Click to add reviewer
        userList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 1) {
                    val selected = userList.selectedValue ?: return
                    val prId = currentPrId ?: return
                    popup.cancel()
                    scope.launch {
                        val result = PrActionService.getInstance(project).addReviewer(prId, selected.name)
                        SwingUtilities.invokeLater {
                            when (result) {
                                is ApiResult.Success -> refreshCurrentPr()
                                is ApiResult.Error -> showNotification("Failed to add reviewer: ${result.message}")
                            }
                        }
                    }
                }
            }
        })

        popup.showUnderneathOf(relativeTo)
    }

    private fun createStatusBadge(status: String): JPanel {
        val color = when (status.uppercase()) {
            "OPEN" -> STATUS_OPEN
            "MERGED" -> STATUS_MERGED
            "DECLINED" -> STATUS_DECLINED
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
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val desktopHints = java.awt.Toolkit.getDefaultToolkit().getDesktopProperty("awt.font.desktophints") as? Map<*, *>
                if (desktopHints != null) {
                    desktopHints.forEach { (k, v) -> if (k is java.awt.RenderingHints.Key && v != null) g2.setRenderingHint(k, v) }
                } else {
                    g2.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                }
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
        val (text, color) = when {
            statuses.isEmpty() -> "No builds" to StatusColors.INFO
            statuses.any { it.state.equals("FAILED", ignoreCase = true) } ->
                "Build Failed" to StatusColors.ERROR
            statuses.any { it.state.equals("INPROGRESS", ignoreCase = true) } ->
                "Building..." to StatusColors.LINK
            statuses.all { it.state.equals("SUCCESSFUL", ignoreCase = true) } ->
                "Build Passed" to StatusColors.SUCCESS
            else -> "Build Unknown" to StatusColors.INFO
        }

        // Store URL of the most relevant build status for click-to-open
        buildStatusUrl = statuses.firstOrNull { it.state.equals("FAILED", ignoreCase = true) }?.url
            ?: statuses.firstOrNull { it.state.equals("INPROGRESS", ignoreCase = true) }?.url
            ?: statuses.firstOrNull()?.url

        buildStatusBadgeContainer.removeAll()
        buildStatusBadgeContainer.add(createBuildStatusBadge(text, color))
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
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val desktopHints = java.awt.Toolkit.getDefaultToolkit().getDesktopProperty("awt.font.desktophints") as? Map<*, *>
                if (desktopHints != null) {
                    desktopHints.forEach { (k, v) -> if (k is java.awt.RenderingHints.Key && v != null) g2.setRenderingHint(k, v) }
                } else {
                    g2.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                }
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
        private val enhanceWithCodyButton = JButton("Enhance with Cody").apply {
            icon = AllIcons.Actions.Lightning
            toolTipText = "Use AI to generate or enhance the PR description"
            addActionListener { enhanceWithCody() }
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
                    add(enhanceWithCodyButton)
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
                    add(enhanceWithCodyButton)
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
            var html = md
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace(Regex("^### (.+)$", RegexOption.MULTILINE), "<h4>$1</h4>")
                .replace(Regex("^## (.+)$", RegexOption.MULTILINE), "<h3>$1</h3>")
                .replace(Regex("^# (.+)$", RegexOption.MULTILINE), "<h2>$1</h2>")
                .replace(Regex("\\*\\*(.+?)\\*\\*"), "<b>$1</b>")
                .replace(Regex("\\*(.+?)\\*"), "<i>$1</i>")
                .replace(Regex("`(.+?)`"), "<code>$1</code>")
                .replace(Regex("((?:^- .+\n?)+)", RegexOption.MULTILINE)) { match ->
                    "<ul>" + match.value.replace(Regex("^- (.+)$", RegexOption.MULTILINE), "<li>$1</li>") + "</ul>"
                }
                .replace(Regex("\\[(.+?)\\]\\((.+?)\\)"), "<a href='$2'>$1</a>")
                .replace("\n\n", "<br><br>")

            val bgColor = StatusColors.htmlColor(CARD_BG)
            val textColor = StatusColors.htmlColor(SECONDARY_TEXT)
            return "<html><body style='font-family: sans-serif; color: $textColor;'>$html</body></html>"
        }

        private fun saveDescription() {
            val prId = currentPrId ?: return
            val version = currentPr?.version ?: 0
            val newDescription = editArea.text
            updateButton.isEnabled = false

            scope.launch {
                PrActionService.getInstance(project).updateDescription(prId, newDescription, version)
                SwingUtilities.invokeLater {
                    updateButton.isEnabled = true
                    currentDescription = newDescription
                    showDescription(newDescription)
                }
            }
        }

        private fun enhanceWithCody() {
            log.info("[PR:Cody] enhanceWithCody() called, prId=${currentPrId}, pr=${currentPr != null}")
            val prId = currentPrId
            if (prId == null) {
                log.warn("[PR:Cody] currentPrId is null — aborting")
                return
            }
            val pr = currentPr
            if (pr == null) {
                log.warn("[PR:Cody] currentPr is null — aborting")
                return
            }
            enhanceWithCodyButton.isEnabled = false
            enhanceWithCodyButton.text = "Generating..."
            log.info("[PR:Cody] Button disabled, starting coroutine")

            scope.launch {
                try {
                    log.info("[PR:Cody] Coroutine started, checking TextGenerationService")
                    val textGen = com.workflow.orchestrator.core.ai.TextGenerationService.getInstance()
                    log.info("[PR:Cody] TextGenerationService: ${textGen?.javaClass?.simpleName ?: "NULL"}")
                    if (textGen == null) {
                        SwingUtilities.invokeLater {
                            enhanceWithCodyButton.isEnabled = true
                            enhanceWithCodyButton.text = "Enhance with Cody"
                            enhanceWithCodyButton.toolTipText = "Cody is not running — start the Cody agent first"
                        }
                        return@launch
                    }

                    log.info("[PR:Cody] Fetching diff for PR #$prId")
                    val diff = PrDetailService.getInstance(project).getDiff(prId)
                    log.info("[PR:Cody] Diff result: ${if (diff == null) "NULL" else "${diff.length} chars"}")
                    if (diff.isNullOrBlank()) {
                        SwingUtilities.invokeLater {
                            enhanceWithCodyButton.isEnabled = true
                            enhanceWithCodyButton.text = "Enhance with Cody"
                            enhanceWithCodyButton.toolTipText = "Could not fetch PR diff from Bitbucket"
                        }
                        return@launch
                    }

                    val truncatedDiff = if (diff.length > 10000) {
                        diff.take(10000) + "\n... (truncated)"
                    } else diff

                    val ticketId = com.workflow.orchestrator.core.settings.PluginSettings
                        .getInstance(project).state.activeTicketId.orEmpty()

                    log.info("[PR:Cody] Calling generatePrDescription: diff=${truncatedDiff.length} chars, ticketId=$ticketId, title=${pr.title}, from=${pr.fromRef?.displayId}, to=${pr.toRef?.displayId}")
                    val enhanced = textGen.generatePrDescription(
                        project = project,
                        diff = truncatedDiff,
                        commitMessages = emptyList(),
                        ticketId = ticketId,
                        ticketSummary = pr.title,
                        sourceBranch = pr.fromRef?.displayId ?: "",
                        targetBranch = pr.toRef?.displayId ?: ""
                    )

                    log.info("[PR:Cody] generatePrDescription result: ${if (enhanced == null) "NULL" else "${enhanced.length} chars"}")
                    SwingUtilities.invokeLater {
                        enhanceWithCodyButton.isEnabled = true
                        enhanceWithCodyButton.text = "Enhance with Cody"
                        if (!enhanced.isNullOrBlank()) {
                            enhanceWithCodyButton.toolTipText = "Use AI to generate or enhance the PR description"
                            enterEditMode()
                            editArea.text = enhanced
                            editArea.caretPosition = 0
                            log.info("[PR:Cody] Description set in edit mode")
                        } else {
                            enhanceWithCodyButton.toolTipText = "Cody returned empty — check IDE log for [Cody:PrChain] errors"
                            log.warn("[PR:Cody] Cody returned null/empty for PR #$prId")
                        }
                    }
                } catch (e: Exception) {
                    log.warn("[PR:Cody] Exception: ${e::class.simpleName}: ${e.message}", e)
                    SwingUtilities.invokeLater {
                        enhanceWithCodyButton.isEnabled = true
                        enhanceWithCodyButton.text = "Enhance with Cody"
                        enhanceWithCodyButton.toolTipText = "Failed: ${e.message}"
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
                        SwingUtilities.invokeLater {
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
                                    com.intellij.notification.NotificationGroupManager.getInstance()
                                        .getNotificationGroup("Workflow Orchestrator")
                                        .createNotification(
                                            "Failed to post comment: ${result.message}",
                                            com.intellij.notification.NotificationType.ERROR
                                        )
                                        .notify(project)
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
            val basePath = project.basePath ?: return
            val fullPath = "$basePath/$relativePath"
            val vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                .findFileByPath(fullPath) ?: run {
                log.warn("[PR:Activity] File not found locally: $fullPath")
                return
            }
            com.intellij.openapi.application.invokeLater {
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

        /** Stored activities for refresh after reply. */
        private var lastActivities: List<BitbucketPrActivity> = emptyList()

        fun showActivities(activities: List<BitbucketPrActivity>) {
            lastActivities = activities
            removeAll()
            contentPanel.removeAll()

            if (activities.isEmpty()) {
                add(emptyLabel, BorderLayout.CENTER)
            } else {
                // Separate inline comments (with anchor) from general activities
                val inlineComments = activities.filter { a ->
                    val anchor = a.commentAnchor ?: a.comment?.anchor
                    anchor != null && anchor.path.isNotBlank()
                }
                val generalActivities = activities.filter { a ->
                    val anchor = a.commentAnchor ?: a.comment?.anchor
                    anchor == null || anchor.path.isBlank()
                }

                // Group inline comments by file:line
                if (inlineComments.isNotEmpty()) {
                    data class AnchorKey(val path: String, val line: Int)

                    val grouped = linkedMapOf<AnchorKey, MutableList<BitbucketPrActivity>>()
                    for (activity in inlineComments) {
                        val anchor = activity.commentAnchor ?: activity.comment?.anchor ?: continue
                        val key = AnchorKey(anchor.path, anchor.line)
                        grouped.getOrPut(key) { mutableListOf() }.add(activity)
                    }

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
                bodyPanel.add(JBLabel(PrListPanel.truncate(commentText, 150)).apply {
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
                bodyPanel.add(JBLabel(PrListPanel.truncate(commentText, 200)).apply {
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
            val commentId = activity.comment?.id?.toInt() ?: return JPanel().apply { isOpaque = false }
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
                        SwingUtilities.invokeLater {
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
                SwingUtilities.invokeLater {
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

    // ---------------------------------------------------------------
    // AI Review sub-panel
    // ---------------------------------------------------------------

    private inner class AiReviewSubPanel : JPanel(BorderLayout()) {
        private val runReviewButton = JButton("Run AI Review").apply {
            icon = AllIcons.Actions.Lightning
        }
        private val resultsArea = JBTextArea().apply {
            lineWrap = true
            wrapStyleWord = true
            isEditable = false
            font = font.deriveFont(JBUI.scale(12).toFloat())
            border = JBUI.Borders.empty(8)
            background = CARD_BG
        }
        private val statusLabel = JBLabel("Click 'Run AI Review' to analyze this PR with Cody.").apply {
            foreground = SECONDARY_TEXT
            horizontalAlignment = SwingConstants.CENTER
            border = JBUI.Borders.emptyTop(20)
        }

        init {
            isOpaque = false

            val topPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
                isOpaque = false
                border = JBUI.Borders.empty(4, 0, 8, 0)
                add(runReviewButton)
            }
            add(topPanel, BorderLayout.NORTH)
            add(statusLabel, BorderLayout.CENTER)

            runReviewButton.addActionListener {
                val prId = currentPrId ?: return@addActionListener
                runReviewButton.isEnabled = false
                statusLabel.text = "Running AI review..."
                remove(statusLabel)
                add(JPanel(FlowLayout(FlowLayout.CENTER)).apply {
                    isOpaque = false
                    add(JBLabel(AnimatedIcon.Default()))
                    add(JBLabel("Analyzing PR diff with Cody..."))
                }, BorderLayout.CENTER)
                revalidate()
                repaint()

                scope.launch {
                    val detailService = PrDetailService.getInstance(project)
                    val diff = detailService.getDiff(prId)

                    SwingUtilities.invokeLater {
                        runReviewButton.isEnabled = true
                        removeAll()
                        add(topPanel, BorderLayout.NORTH)

                        if (diff.isNullOrBlank()) {
                            add(JBLabel("Could not fetch PR diff for review.").apply {
                                foreground = SECONDARY_TEXT
                                horizontalAlignment = SwingConstants.CENTER
                                border = JBUI.Borders.emptyTop(20)
                            }, BorderLayout.CENTER)
                        } else {
                            // TODO: Wire to Cody agent for actual AI review
                            resultsArea.text = "AI Review is not yet connected to Cody.\n\n" +
                                "Diff size: ${diff.length} characters\n" +
                                "This feature will analyze the diff and provide:\n" +
                                "- Code quality observations\n" +
                                "- Potential bugs\n" +
                                "- Security considerations\n" +
                                "- Suggestions for improvement"
                            resultsArea.caretPosition = 0
                            add(JBScrollPane(resultsArea).apply {
                                border = JBUI.Borders.empty()
                            }, BorderLayout.CENTER)
                        }
                        revalidate()
                        repaint()
                    }
                }
            }
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
            val vetoText = mergeStatus.vetoes.joinToString("\n") { it.summaryMessage }
            val warningLabel = JBLabel("<html><b>Warnings:</b><br>${vetoText.replace("\n", "<br>")}</html>").apply {
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

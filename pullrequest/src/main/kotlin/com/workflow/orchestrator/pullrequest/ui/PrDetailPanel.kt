package com.workflow.orchestrator.pullrequest.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
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
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.core.bitbucket.BitbucketMergeStatus
import com.workflow.orchestrator.core.bitbucket.BitbucketMergeStrategy
import com.workflow.orchestrator.core.bitbucket.BitbucketPrDetail
import com.workflow.orchestrator.core.bitbucket.BitbucketPrRef
import com.workflow.orchestrator.core.settings.ConnectionSettings
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.pullrequest.service.PrActionService
import com.workflow.orchestrator.pullrequest.service.PrDetailService
import kotlinx.coroutines.*
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import java.text.SimpleDateFormat
import java.util.*
import javax.swing.*

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

        private val SECONDARY_TEXT = StatusColors.SECONDARY_TEXT
        private val CARD_BG = JBColor(0xF7F8FA, 0x2B2D30)
        private val BORDER_COLOR = JBColor(0xD1D9E0, 0x444D56)
        private val LINK_COLOR = StatusColors.LINK
        private val STATUS_OPEN = StatusColors.OPEN
        private val STATUS_MERGED = StatusColors.MERGED
        private val STATUS_DECLINED = StatusColors.DECLINED
        private val APPROVED_COLOR = StatusColors.SUCCESS

        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm")
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

    // Header
    private val titleLabel = JBLabel("").apply {
        font = font.deriveFont(Font.BOLD, JBUI.scale(16).toFloat())
        foreground = JBColor.foreground()
    }
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
    private val openInBrowserButton = JButton("Open in Browser").apply {
        icon = AllIcons.General.Web
    }

    // Content toggle buttons
    private val descriptionToggle = JToggleButton("Description")
    private val activityToggle = JToggleButton("Activity")
    private val filesToggle = JToggleButton("Files")
    private val aiReviewToggle = JToggleButton("AI Review")
    private val toggleGroup = ButtonGroup()

    // Content panels
    private val contentCards = JPanel(CardLayout()).apply {
        isOpaque = false
    }
    private val descriptionSubPanel = DescriptionSubPanel()
    private val activitySubPanel = ActivitySubPanel()
    private val filesSubPanel = FilesSubPanel()
    private val aiReviewSubPanel = AiReviewSubPanel()

    init {
        isOpaque = false
        background = JBColor.PanelBackground

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
        }
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

        // Title + PR ID
        val headerSection = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyBottom(4)
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(60))
            alignmentX = Component.LEFT_ALIGNMENT
        }
        headerSection.add(titleLabel, BorderLayout.NORTH)
        val metaRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
            isOpaque = false
        }
        metaRow.add(prIdLabel)
        metaRow.add(statusBadgeContainer)
        metaRow.add(branchLabel)
        headerSection.add(metaRow, BorderLayout.CENTER)
        contentPanel.add(headerSection)

        // Reviewers row
        val reviewersRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(24))
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.emptyBottom(4)
        }
        reviewersRow.add(reviewersLabel)
        contentPanel.add(reviewersRow)

        // Action buttons row
        val actionsRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            isOpaque = false
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(36))
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.emptyBottom(8)
        }
        actionsRow.add(approveButton)
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
        toggleGroup.add(aiReviewToggle)
        toggleRow.add(descriptionToggle)
        toggleRow.add(activityToggle)
        toggleRow.add(filesToggle)
        toggleRow.add(aiReviewToggle)
        contentPanel.add(toggleRow)

        // Content cards
        contentCards.add(descriptionSubPanel, "description")
        contentCards.add(activitySubPanel, "activity")
        contentCards.add(filesSubPanel, "files")
        contentCards.add(aiReviewSubPanel, "aiReview")
        contentCards.alignmentX = Component.LEFT_ALIGNMENT
        contentPanel.add(contentCards)

        // Toggle listeners
        descriptionToggle.addActionListener { (contentCards.layout as CardLayout).show(contentCards, "description") }
        activityToggle.addActionListener { (contentCards.layout as CardLayout).show(contentCards, "activity") }
        filesToggle.addActionListener { (contentCards.layout as CardLayout).show(contentCards, "files") }
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
                PrActionService.getInstance(project).approve(prId)
                // Refresh to reflect the update
                SwingUtilities.invokeLater {
                    approveButton.text = "Approved"
                    approveButton.isEnabled = false
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
                            declineButton.isEnabled = false
                        }
                    }
                }
            }
        }

        declineButton.addActionListener {
            val prId = currentPrId ?: return@addActionListener
            val version = currentPr?.version ?: 0
            val confirm = JOptionPane.showConfirmDialog(
                this,
                "Decline PR #$prId?",
                "Confirm Decline",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE
            )
            if (confirm != JOptionPane.YES_OPTION) return@addActionListener
            scope.launch {
                PrActionService.getInstance(project).decline(prId, version)
                SwingUtilities.invokeLater {
                    mergeButton.isEnabled = false
                    approveButton.isEnabled = false
                    declineButton.isEnabled = false
                }
            }
        }

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
        approveButton.text = "Approve"
        mergeButton.isEnabled = isOpen
        declineButton.isEnabled = isOpen
    }

    private fun renderReviewers(pr: BitbucketPrDetail) {
        if (pr.reviewers.isEmpty()) {
            reviewersLabel.text = "No reviewers assigned"
            return
        }
        val reviewerText = pr.reviewers.joinToString(", ") { reviewer ->
            val name = reviewer.user.displayName.ifBlank { reviewer.user.name }
            val statusIcon = if (reviewer.approved) "\u2713" else "\u25CB"
            "$statusIcon $name"
        }
        reviewersLabel.text = "Reviewers: $reviewerText"
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
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB)
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

    // ---------------------------------------------------------------
    // Description sub-panel
    // ---------------------------------------------------------------

    private inner class DescriptionSubPanel : JPanel(BorderLayout()) {
        private val descriptionArea = JBTextArea().apply {
            lineWrap = true
            wrapStyleWord = true
            isEditable = false
            font = font.deriveFont(JBUI.scale(12).toFloat())
            border = JBUI.Borders.empty(8)
            background = CARD_BG
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
                descriptionArea.text = description
                descriptionArea.caretPosition = 0
                add(JBScrollPane(descriptionArea).apply {
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
        private val activityListModel = DefaultListModel<ActivityDisplayItem>()
        private val activityList = JBList(activityListModel).apply {
            cellRenderer = ActivityCellRenderer()
            fixedCellHeight = -1 // Variable height — inline comments need more space
            border = JBUI.Borders.empty()
            isOpaque = false
        }
        private val emptyLabel = JBLabel("No activity yet.").apply {
            foreground = JBUI.CurrentTheme.Label.disabledForeground()
            horizontalAlignment = SwingConstants.CENTER
            border = JBUI.Borders.emptyTop(20)
        }

        init {
            isOpaque = false
            add(emptyLabel, BorderLayout.CENTER)

            // Double-click on inline comment → navigate to file:line
            activityList.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount == 2) {
                        val index = activityList.locationToIndex(e.point)
                        if (index < 0) return
                        val item = activityListModel.getElementAt(index)
                        val path = item.anchorPath ?: return
                        navigateToFile(path, item.anchorLine)
                    }
                }
            })
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

        fun showActivities(activities: List<com.workflow.orchestrator.core.bitbucket.BitbucketPrActivity>) {
            removeAll()
            activityListModel.clear()

            if (activities.isEmpty()) {
                add(emptyLabel, BorderLayout.CENTER)
            } else {
                for (activity in activities) {
                    val authorName = activity.comment?.author?.displayName
                        ?: activity.user.displayName
                    val commentText = activity.comment?.text ?: ""
                    val timestamp = if (activity.createdDate > 0) {
                        DATE_FORMAT.format(Date(activity.createdDate))
                    } else ""

                    // Extract inline comment anchor (file + line)
                    val anchor = activity.commentAnchor ?: activity.comment?.anchor
                    val anchorPath = anchor?.path?.takeIf { it.isNotBlank() }
                    val anchorLine = anchor?.line ?: 0

                    activityListModel.addElement(ActivityDisplayItem(
                        userName = authorName,
                        action = activity.action,
                        timestamp = timestamp,
                        commentText = commentText,
                        anchorPath = anchorPath,
                        anchorLine = anchorLine
                    ))
                }
                add(JBScrollPane(activityList).apply {
                    border = JBUI.Borders.empty()
                    isOpaque = false
                    viewport.isOpaque = false
                }, BorderLayout.CENTER)
            }
            revalidate()
            repaint()
        }
    }

    private data class ActivityDisplayItem(
        val userName: String,
        val action: String,
        val timestamp: String,
        val commentText: String,
        /** File path for inline code comments (null for general activity). */
        val anchorPath: String? = null,
        /** Line number for inline code comments (0 = no line). */
        val anchorLine: Int = 0
    )

    private class ActivityCellRenderer : ListCellRenderer<ActivityDisplayItem> {
        override fun getListCellRendererComponent(
            list: JList<out ActivityDisplayItem>,
            value: ActivityDisplayItem,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            return JPanel(BorderLayout()).apply {
                isOpaque = isSelected
                if (isSelected) {
                    background = list.selectionBackground
                }
                border = JBUI.Borders.empty(6, 8)

                val topRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
                    isOpaque = false
                }
                topRow.add(JBLabel(value.userName).apply {
                    font = font.deriveFont(Font.BOLD, JBUI.scale(11).toFloat())
                    if (isSelected) foreground = list.selectionForeground
                })
                topRow.add(JBLabel(formatAction(value.action)).apply {
                    font = font.deriveFont(JBUI.scale(11).toFloat())
                    foreground = if (isSelected) list.selectionForeground else SECONDARY_TEXT
                })
                topRow.add(JBLabel(value.timestamp).apply {
                    font = font.deriveFont(JBUI.scale(10).toFloat())
                    foreground = if (isSelected) list.selectionForeground else SECONDARY_TEXT
                })
                add(topRow, BorderLayout.NORTH)

                val contentPanel = JPanel().apply {
                    layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
                    isOpaque = false
                    border = JBUI.Borders.emptyLeft(JBUI.scale(8))
                }

                // Show file:line anchor for inline code comments
                if (value.anchorPath != null) {
                    val fileName = value.anchorPath.substringAfterLast('/')
                    val lineText = if (value.anchorLine > 0) ":${value.anchorLine}" else ""
                    contentPanel.add(JBLabel("$fileName$lineText").apply {
                        font = font.deriveFont(JBUI.scale(10).toFloat())
                        foreground = StatusColors.LINK
                        icon = AllIcons.FileTypes.Java
                        iconTextGap = JBUI.scale(4)
                        toolTipText = "Double-click to navigate to ${value.anchorPath}$lineText"
                    })
                }

                if (value.commentText.isNotBlank()) {
                    contentPanel.add(JBLabel(PrListPanel.truncate(value.commentText, 150)).apply {
                        font = font.deriveFont(JBUI.scale(11).toFloat())
                        foreground = if (isSelected) list.selectionForeground else SECONDARY_TEXT
                        if (value.commentText.length > 150) toolTipText = value.commentText
                    })
                }

                if (contentPanel.componentCount > 0) {
                    add(contentPanel, BorderLayout.CENTER)
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

        init {
            isOpaque = false
            add(emptyLabel, BorderLayout.CENTER)
        }

        fun showChanges(changes: List<com.workflow.orchestrator.core.bitbucket.BitbucketPrChange>) {
            removeAll()
            filesListModel.clear()

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
    }

    private data class FileDisplayItem(
        val fileName: String,
        val dirPath: String,
        val changeType: String
    )

    private class FileCellRenderer : ListCellRenderer<FileDisplayItem> {
        override fun getListCellRendererComponent(
            list: JList<out FileDisplayItem>,
            value: FileDisplayItem,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            return JPanel(BorderLayout()).apply {
                isOpaque = isSelected
                if (isSelected) {
                    background = JBColor(0xDEE9FC, 0x2D3548)
                }
                border = JBUI.Borders.empty(2, 8)

                val leftRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
                    isOpaque = false
                }

                // Change type badge
                val badge = createChangeTypeBadge(value.changeType)
                leftRow.add(badge)

                // File name
                leftRow.add(JBLabel(value.fileName).apply {
                    font = font.deriveFont(JBUI.scale(12).toFloat())
                    foreground = JBColor.foreground()
                })

                // Directory path
                if (value.dirPath.isNotBlank()) {
                    leftRow.add(JBLabel(value.dirPath).apply {
                        font = font.deriveFont(JBUI.scale(10).toFloat())
                        foreground = SECONDARY_TEXT
                    })
                }

                add(leftRow, BorderLayout.CENTER)
            }
        }

        private fun createChangeTypeBadge(type: String): JBLabel {
            val letter = when (type.uppercase()) {
                "ADD" -> "A"
                "MODIFY" -> "M"
                "DELETE" -> "D"
                "RENAME" -> "R"
                "COPY" -> "C"
                else -> "?"
            }
            val color = when (type.uppercase()) {
                "ADD" -> StatusColors.SUCCESS
                "MODIFY" -> StatusColors.LINK
                "DELETE" -> StatusColors.ERROR
                "RENAME" -> StatusColors.WARNING
                else -> SECONDARY_TEXT
            }
            return JBLabel(letter).apply {
                font = font.deriveFont(Font.BOLD, JBUI.scale(10).toFloat())
                foreground = color
                border = JBUI.Borders.empty(0, 2, 0, 4)
            }
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

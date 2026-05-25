package com.workflow.orchestrator.sonar.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.icons.AllIcons
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.core.model.workflow.PrRef
import com.workflow.orchestrator.core.model.workflow.QualityScope
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.settings.RepoConfig
import com.workflow.orchestrator.core.ui.ComboBoxWidth
import com.workflow.orchestrator.core.ui.StatusColors
import com.workflow.orchestrator.core.ui.bindBoundedWidth
import com.workflow.orchestrator.core.workflow.WorkflowContextService
import com.workflow.orchestrator.core.workflow.ui.ReadOnlyBanner
import com.workflow.orchestrator.sonar.model.QualityGateStatus
import com.workflow.orchestrator.sonar.model.SonarState
import com.workflow.orchestrator.sonar.service.SonarDataService
import com.intellij.openapi.application.EDT
import kotlinx.coroutines.*
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.BoxLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JPanel

class QualityDashboardPanel(
    private val project: Project
) : JPanel(BorderLayout()), Disposable {

    private val settings = PluginSettings.getInstance(project)
    private val dataService = SonarDataService.getInstance(project)
    private val workflowContextService = WorkflowContextService.getInstance(project)
    // Phase 5 T15: amber banner shown when interactionMode == ReadOnly (spec §7.1).
    private val readOnlyBanner = ReadOnlyBanner(project).also {
        com.intellij.openapi.util.Disposer.register(this, it)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.EDT)

    // Repo selector for multi-repo support — show all configured repos
    private val allRepos: List<RepoConfig> = settings.getRepos().filter { it.isConfigured }
    private val repoSelector: ComboBox<String>? = if (allRepos.size > 1) {
        ComboBox(DefaultComboBoxModel(allRepos.map { it.displayLabel }.toTypedArray())).apply {
            val primaryIndex = allRepos.indexOfFirst { it.isPrimary }.takeIf { it >= 0 } ?: 0
            selectedIndex = primaryIndex
            bindBoundedWidth(ComboBoxWidth.WIDE)
        }
    } else null

    private var suppressRepoSelectorListener = false

    // Hint label shown when no PR or no Sonar key is available
    private val qualityHintLabel = JBLabel("").apply {
        foreground = StatusColors.SECONDARY_TEXT
        font = font.deriveFont(java.awt.Font.ITALIC, 11f)
        border = JBUI.Borders.empty(12, 12)
        isVisible = false
    }

    // UI components
    private val headerLabel = JBLabel("").apply {
        font = font.deriveFont(Font.BOLD, 11f)
        foreground = StatusColors.SECONDARY_TEXT
        border = JBUI.Borders.empty(4, 8)
    }
    private val branchInfoLabel = JBLabel("").apply {
        font = font.deriveFont(Font.PLAIN, 11f)
        border = JBUI.Borders.empty(2, 8)
    }
    private val branchWarningLabel = JBLabel("").apply {
        font = font.deriveFont(Font.ITALIC, 10f)
        foreground = StatusColors.WARNING
        border = JBUI.Borders.empty(0, 8, 4, 8)
        isVisible = false
    }
    private val analysisStatusLabel = JBLabel("").apply {
        font = font.deriveFont(Font.PLAIN, 10f)
        border = JBUI.Borders.empty(0, 8, 2, 8)
        isVisible = false
    }

    /**
     * Permission-aware empty state for analysis history. Shown when the active
     * Sonar token doesn't have Administer Project permission and `/api/ce/activity`
     * 403'd. Click opens the project's Sonar permissions page.
     */
    private val analysisPermissionHintLabel = JBLabel("").apply {
        font = font.deriveFont(Font.PLAIN, 10f)
        foreground = StatusColors.SECONDARY_TEXT
        border = JBUI.Borders.empty(0, 8, 4, 8)
        isVisible = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    }
    private val newCodePeriodLabel = JBLabel("").apply {
        font = font.deriveFont(Font.PLAIN, 10f)
        foreground = StatusColors.SECONDARY_TEXT
        border = JBUI.Borders.empty(0, 8, 4, 8)
        isVisible = false
    }
    private val overviewPanel = OverviewPanel(project)
    private val issueListPanel = IssueListPanel(project).also {
        com.intellij.openapi.util.Disposer.register(this, it)
    }
    private val coverageTablePanel = CoverageTablePanel(project).also {
        com.intellij.openapi.util.Disposer.register(this, it)
    }
    private val statusLabel = JBLabel("Loading...")
    private val loadingIcon = JBLabel(AnimatedIcon.Default()).apply { isVisible = false }

    private val gateBanner = GateStatusBanner()
    private lateinit var tabbedPane: JBTabbedPane

    // Tab-aware rendering: track which tab is visible and what state was last rendered per tab
    private var selectedTabIndex = 0
    private var lastRenderedState: SonarState? = null
    private var overviewStale = true
    private var issuesStale = true
    private var coverageStale = true

    // Toggle buttons
    private val newCodeButton = JButton("New Code").apply {
        isFocusPainted = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        mnemonic = java.awt.event.KeyEvent.VK_N
    }
    private val overallButton = JButton("Overall").apply {
        isFocusPainted = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        mnemonic = java.awt.event.KeyEvent.VK_O
    }

    init {
        border = JBUI.Borders.empty()

        // Header: left label + right toggle + refresh
        // Use FlowLayout so elements wrap at narrow widths instead of overlapping
        val gutterMarkersCheckbox = JCheckBox("Gutter Markers").apply {
            isSelected = settings.state.coverageGutterMarkersEnabled
            toolTipText = "Show coverage markers in the editor gutter"
            isFocusPainted = false
            addActionListener {
                settings.state.coverageGutterMarkersEnabled = isSelected
                // Re-trigger daemon analysis to show/hide markers
                com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).selectedTextEditor?.let { editor ->
                    val psiFile = com.intellij.psi.PsiDocumentManager.getInstance(project).getPsiFile(editor.document)
                    if (psiFile != null) {
                        com.intellij.codeInsight.daemon.DaemonCodeAnalyzer.getInstance(project).restart(psiFile)
                    }
                }
            }
        }

        val headerPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), JBUI.scale(2))).apply {
            border = JBUI.Borders.empty(4, 8)
            if (repoSelector != null) {
                add(repoSelector)
            }
            add(headerLabel)
            add(newCodeButton)
            add(overallButton)
            add(gutterMarkersCheckbox)
        }

        // Branch info bar
        val branchInfoPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(branchInfoLabel)
            add(branchWarningLabel)
            add(analysisStatusLabel)
            add(analysisPermissionHintLabel)
            add(newCodePeriodLabel)
        }

        // Analysis-history permission hint click → open Sonar project permissions page
        analysisPermissionHintLabel.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                val state = lastRenderedState ?: return
                val baseUrl = state.sonarBaseUrl.takeIf { it.isNotBlank() } ?: return
                val key = state.projectKey.takeIf { it.isNotBlank() } ?: return
                val url = "$baseUrl/project_roles?id=${java.net.URLEncoder.encode(key, "UTF-8")}"
                com.intellij.ide.BrowserUtil.browse(url)
            }
        })

        // Top section: read-only banner + toolbar + branch info + gate banner + hint
        // Phase 5 T15: ReadOnly banner is the FIRST child so it stays at the top of the
        // panel even as the toolbar / branch info / gate banner change height.
        val topSection = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(readOnlyBanner)
            val toolbarRow = JPanel(BorderLayout()).apply {
                add(headerPanel, BorderLayout.CENTER)
                add(createToolbar(), BorderLayout.EAST)
            }
            add(toolbarRow)
            add(branchInfoPanel)
            add(gateBanner)
            add(qualityHintLabel)
        }
        add(topSection, BorderLayout.NORTH)

        // Wire gate banner callback for cross-tab navigation. Coverage and issue
        // conditions both honor the failing metric's `new_*` prefix so the user
        // lands on the same scope the gate evaluated.
        gateBanner.onShowBlockingIssues = { filter ->
            if (filter.newCodeMode) dataService.setNewCodeMode(true)
            if (filter.isCoverageCondition) {
                tabbedPane.selectedIndex = 2  // Coverage tab
            } else {
                tabbedPane.selectedIndex = 1  // Issues tab
                filter.issueType?.let { issueListPanel.applyPreFilter(it, filter.newCodeMode) }
            }
        }

        // Sub-tabbed pane — track selected tab for lazy rendering
        tabbedPane = JBTabbedPane().apply {
            addTab("Overview", overviewPanel)
            addTab("Issues", issueListPanel)
            addTab("Coverage", coverageTablePanel)
            addChangeListener {
                val newIndex = selectedIndex
                if (newIndex != selectedTabIndex) {
                    selectedTabIndex = newIndex
                    // If the newly visible tab has stale data, update it now
                    val currentState = lastRenderedState ?: return@addChangeListener
                    when (newIndex) {
                        0 -> if (overviewStale) { overviewPanel.update(currentState); overviewStale = false }
                        1 -> if (issuesStale) {
                            issueListPanel.update(currentState.activeIssues, currentState.activeTotalIssueCount)
                            issueListPanel.updateHotspots(currentState.securityHotspots)
                            issuesStale = false
                        }
                        2 -> if (coverageStale) {
                            coverageTablePanel.update(currentState.activeFileCoverage, currentState.newCodeMode, currentState.totalCoverageFileCount)
                            coverageStale = false
                        }
                    }
                }
            }
        }
        add(tabbedPane, BorderLayout.CENTER)

        // Status bar
        val statusPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
            border = JBUI.Borders.empty(2, 8)
            add(loadingIcon)
            add(statusLabel)
        }
        add(statusPanel, BorderLayout.SOUTH)

        // Toggle listeners
        newCodeButton.addActionListener {
            dataService.setNewCodeMode(true)
            updateToggleAppearance(true)
        }
        overallButton.addActionListener {
            dataService.setNewCodeMode(false)
            updateToggleAppearance(false)
        }

        // Initial toggle state
        updateToggleAppearance(true)

        // Repo selector listener — switch sonar project key when a different repo is selected
        repoSelector?.addActionListener {
            if (suppressRepoSelectorListener) return@addActionListener
            val selectedIndex = repoSelector.selectedIndex
            if (selectedIndex < 0 || selectedIndex >= allRepos.size) return@addActionListener

            val selectedRepo = allRepos[selectedIndex]
            val repoName = selectedRepo.displayLabel
            val sonarKey = selectedRepo.sonarProjectKey?.takeIf { it.isNotBlank() }

            // Priority 1: No sonar key configured
            if (sonarKey == null) {
                showQualityHint("SonarQube project key not configured for $repoName \u2014 configure in Settings > CI/CD")
                return@addActionListener
            }

            // Priority 2: Check if a PR is focused for this repo. Phase 5 T11 reads
            // focusPr from WorkflowContextService instead of the legacy event-bus cache.
            // One-shot direct flow read is correct here: this listener fires on user
            // dropdown action, not on flow emission.
            val focusPr = workflowContextService.state.value.focusPr
            if (focusPr != null && focusPr.repoName == repoName && focusPr.fromBranch.isNotBlank()) {
                qualityHintLabel.isVisible = false
                tabbedPane.isVisible = true
                statusLabel.text = "Switching to $sonarKey..."
                loadingIcon.isVisible = true
                dataService.refreshForBranch(focusPr.fromBranch, sonarKey)
            } else {
                showQualityHint("No PR selected for $repoName \u2014 select one in the PR tab")
            }
        }

        // Subscribe to state updates — debounce to coalesce rapid state changes
        // (e.g., branch change + PR selection firing within 500ms) into a single UI update.
        // scope uses Dispatchers.EDT, so the collector already runs on the EDT — the
        // redundant invokeLater() is removed to prevent double-dispatch ordering issues (F-9 fix).
        @OptIn(FlowPreview::class)
        scope.launch {
            dataService.stateFlow
                .debounce(300)
                .collect { state ->
                    updateUI(state)
                }
        }

        // Auto-sync repo selector to focused PR (Phase 5 T11). Narrow subscription on
        // focusPr.repoName so unrelated state changes (e.g., activeBranch) don't fire.
        scope.launch {
            workflowContextService.state
                .map { it.focusPr }
                .distinctUntilChanged()
                .collect { pr ->
                    if (pr != null) onFocusPrChanged(pr)
                }
        }

        // Refresh Sonar data when the focused quality scope changes (Phase 5 T11).
        // Narrow subscription on focusQualityScope keeps heavy HTTP refreshes scoped
        // to actual scope changes, not every state mutation.
        scope.launch {
            workflowContextService.state
                .map { it.focusQualityScope }
                .distinctUntilChanged()
                .collect { qualityScope ->
                    renderForQualityScope(qualityScope)
                }
        }

        // Initial refresh
        refreshData()
    }

    private fun updateToggleAppearance(newCodeMode: Boolean) {
        val selectedBg = StatusColors.LINK
        val selectedFg = JBColor(Color.WHITE, Color(0x1e, 0x1e, 0x2e))
        val normalBg = JBColor(Color(0xE0, 0xE0, 0xE0), Color(0x45, 0x47, 0x5a))
        val normalFg = StatusColors.SECONDARY_TEXT

        if (newCodeMode) {
            newCodeButton.background = selectedBg
            newCodeButton.foreground = selectedFg
            overallButton.background = normalBg
            overallButton.foreground = normalFg
        } else {
            newCodeButton.background = normalBg
            newCodeButton.foreground = normalFg
            overallButton.background = selectedBg
            overallButton.foreground = selectedFg
        }
    }

    private fun createToolbar(): javax.swing.JComponent {
        val group = DefaultActionGroup()
        group.add(object : AnAction("Refresh", "Refresh SonarQube data", AllIcons.Actions.Refresh) {
            override fun actionPerformed(e: AnActionEvent) {
                refreshData()
            }
        })
        val toolbar = ActionManager.getInstance()
            .createActionToolbar(ActionPlaces.TOOLBAR, group, true)
        toolbar.targetComponent = this
        return toolbar.component
    }

    fun refreshData() {
        // Refresh whatever the panel is currently anchored on — the focused PR's
        // QualityScope. No fall-through to the editor's branch + scalar projectKey:
        // multi-repo users would see Sonar data for a repo they aren't looking at.
        // The auto-render via the focusQualityScope flow handles the active path on
        // boot and on PR change; this is the manual button.
        //
        // EDT read of `state.value`; cascade writes happen on the service scope. A
        // click during an in-flight cascade reads the previous scope; the user's
        // next click sees the new value. Cascade completes in milliseconds, so
        // worst-case stale-by-one-click is acceptable.
        val scope = workflowContextService.state.value.focusQualityScope
        val branch = scope?.branchName?.takeIf { it.isNotBlank() }
        if (scope == null || branch == null) {
            statusLabel.text = "Select a PR to refresh quality data"
            loadingIcon.isVisible = false
            return
        }
        statusLabel.text = "Refreshing..."
        loadingIcon.isVisible = true
        dataService.refreshForBranch(branch, scope.sonarProjectKey)
    }

    private fun updateUI(state: SonarState) {
        if (state.projectKey.isEmpty()) {
            loadingIcon.isVisible = false
            // Check if hint is already showing (e.g., "no PR selected")
            if (!qualityHintLabel.isVisible) {
                statusLabel.text = "Configure SonarQube project key in Settings > CI/CD."
                statusLabel.foreground = JBUI.CurrentTheme.Label.disabledForeground()
            }
            return
        }

        // Hide hint when valid data arrives
        qualityHintLabel.isVisible = false
        tabbedPane.isVisible = true

        val prev = lastRenderedState

        // --- Header section (always updated — lightweight label text changes) ---
        val gateIcon = when (state.qualityGate.status) {
            QualityGateStatus.PASSED -> "\u2713"
            QualityGateStatus.FAILED -> "\u2717"
            QualityGateStatus.NONE -> "—"
        }

        val modeLabel = if (state.newCodeMode) "NEW CODE" else "OVERALL"
        val coverage = state.activeOverallCoverage
        val issueCount = state.activeIssues.size
        headerLabel.text = "${state.projectKey.uppercase()}  $gateIcon $modeLabel  \u2022  COVERAGE: ${"%.1f".format(coverage.lineCoverage)}%  \u2022  ISSUES: $issueCount"

        updateToggleAppearance(state.newCodeMode)

        // --- Branch info bar (only update if branch-related fields changed) ---
        val branchChanged = prev == null
            || prev.branch != state.branch
            || prev.currentBranchAnalyzed != state.currentBranchAnalyzed
            || prev.currentBranchAnalysisDate != state.currentBranchAnalysisDate
            || prev.lastAnalysisForBranch != state.lastAnalysisForBranch
            || prev.newCodePeriod != state.newCodePeriod
            || prev.branches != state.branches
            || prev.analysisHistoryForbidden != state.analysisHistoryForbidden

        if (branchChanged) {
            updateBranchInfo(state)
        }

        // --- Determine which sub-tabs have changed data ---
        val overviewChanged = prev == null
            || prev.qualityGate != state.qualityGate
            || prev.activeOverallCoverage != state.activeOverallCoverage
            || prev.activeIssues != state.activeIssues
            || prev.newCodeMode != state.newCodeMode
            || prev.projectHealth != state.projectHealth
            || prev.securityHotspots != state.securityHotspots

        val issuesChanged = prev == null
            || prev.activeIssues != state.activeIssues
            || prev.activeTotalIssueCount != state.activeTotalIssueCount
            || prev.securityHotspots != state.securityHotspots

        val coverageChanged = prev == null
            || prev.activeFileCoverage != state.activeFileCoverage
            || prev.fileCoverage != state.fileCoverage
            || prev.newCodeMode != state.newCodeMode
            || prev.totalCoverageFileCount != state.totalCoverageFileCount

        // Update gate banner
        gateBanner.update(state.qualityGate)

        // Show analysis failure hint if the last analysis for this branch failed
        val lastAnalysis = state.lastAnalysisForBranch
        if (lastAnalysis != null && lastAnalysis.status == "FAILED") {
            val errorMsg = lastAnalysis.errorMessage ?: "Unknown error"
            qualityHintLabel.text = "SonarQube analysis failed: $errorMsg"
            qualityHintLabel.foreground = StatusColors.ERROR
            qualityHintLabel.isVisible = true
        } else if (!state.currentBranchAnalyzed && state.issues.isEmpty()) {
            qualityHintLabel.text = "No SonarQube analysis found for branch '${state.branch}'. Analysis may be pending."
            qualityHintLabel.foreground = StatusColors.WARNING
            qualityHintLabel.isVisible = true
        } else {
            qualityHintLabel.foreground = StatusColors.SECONDARY_TEXT
        }

        // Mark stale flags for tabs whose data changed
        if (overviewChanged) overviewStale = true
        if (issuesChanged) issuesStale = true
        if (coverageChanged) coverageStale = true

        // Only update the CURRENTLY VISIBLE sub-tab; others will update on tab switch
        when (selectedTabIndex) {
            0 -> if (overviewStale) { overviewPanel.update(state); overviewStale = false }
            1 -> if (issuesStale) {
                issueListPanel.update(state.activeIssues, state.activeTotalIssueCount)
                issueListPanel.updateHotspots(state.securityHotspots)
                issuesStale = false
            }
            2 -> if (coverageStale) {
                coverageTablePanel.update(state.activeFileCoverage, state.newCodeMode, state.totalCoverageFileCount)
                coverageStale = false
            }
        }

        lastRenderedState = state

        loadingIcon.isVisible = false
        val elapsed = java.time.Duration.between(state.lastUpdated, java.time.Instant.now())
        statusLabel.text = "Updated ${elapsed.seconds}s ago"
    }

    /**
     * Updates branch info labels, analysis status, new code period, and branch tooltips.
     * Extracted from updateUI to allow skipping when branch data hasn't changed.
     */
    private fun updateBranchInfo(state: SonarState) {
        // Update branch info bar
        if (state.currentBranchAnalyzed) {
            val analysisDate = state.currentBranchAnalysisDate?.let { formatAnalysisDate(it) } ?: "unknown"
            branchInfoLabel.text = "Branch: ${state.branch} \u2014 Last analyzed: $analysisDate"
            branchInfoLabel.foreground = JBColor.foreground()
            branchWarningLabel.isVisible = false
        } else {
            branchInfoLabel.text = "Branch: ${state.branch} \u2014 Not analyzed"
            branchInfoLabel.foreground = StatusColors.WARNING
            branchWarningLabel.text = "\u26A0 This branch has not been analyzed by SonarQube. Data shown is from the last available analysis."
            branchWarningLabel.isVisible = true
        }

        // Show CE analysis status for the current branch
        val lastAnalysis = state.lastAnalysisForBranch
        if (lastAnalysis != null) {
            when (lastAnalysis.status) {
                "FAILED" -> {
                    val errorMsg = lastAnalysis.errorMessage ?: "Unknown error"
                    analysisStatusLabel.text = "\u2717 Last analysis failed: $errorMsg"
                    analysisStatusLabel.foreground = StatusColors.ERROR
                    analysisStatusLabel.isVisible = true
                }
                "PENDING", "IN_PROGRESS" -> {
                    analysisStatusLabel.text = "\u23F3 Analysis in progress..."
                    analysisStatusLabel.foreground = StatusColors.WARNING
                    analysisStatusLabel.isVisible = true
                }
                "SUCCESS" -> {
                    val timeStr = lastAnalysis.executionTimeMs?.let { "${it / 1000}s" } ?: "N/A"
                    analysisStatusLabel.text = "\u2713 Last analysis: $timeStr"
                    analysisStatusLabel.foreground = StatusColors.SUCCESS
                    analysisStatusLabel.isVisible = true
                }
                "CANCELED" -> {
                    analysisStatusLabel.text = "\u2014 Last analysis was canceled"
                    analysisStatusLabel.foreground = StatusColors.SECONDARY_TEXT
                    analysisStatusLabel.isVisible = true
                }
                else -> {
                    analysisStatusLabel.isVisible = false
                }
            }
        } else {
            analysisStatusLabel.isVisible = false
        }

        // Permission-aware empty state for /api/ce/activity 403. Without this,
        // the "Last analysis" indicator goes silently blank for non-admin tokens.
        if (state.analysisHistoryForbidden && lastAnalysis == null) {
            val linkColor = StatusColors.htmlColor(StatusColors.LINK as JBColor)
            analysisPermissionHintLabel.text = "<html>Analysis history hidden \u2014 token lacks <b>Administer Project</b> permission. " +
                "<font color='$linkColor'><u>Open Sonar permissions</u></font></html>"
            analysisPermissionHintLabel.isVisible = true
        } else {
            analysisPermissionHintLabel.isVisible = false
        }

        // Show new code period info
        val ncp = state.newCodePeriod
        if (ncp != null) {
            val periodDesc = when (ncp.type) {
                "REFERENCE_BRANCH" -> "compared to ${ncp.value}"
                "NUMBER_OF_DAYS" -> "last ${ncp.value} days"
                "PREVIOUS_VERSION" -> "since version ${ncp.value}"
                "SPECIFIC_ANALYSIS" -> "since specific analysis"
                else -> ncp.type.lowercase().replace('_', ' ')
            }
            val inheritedSuffix = if (ncp.inherited) " (inherited)" else ""
            newCodePeriodLabel.text = "New code: $periodDesc$inheritedSuffix"
            newCodePeriodLabel.isVisible = true
        } else {
            newCodePeriodLabel.isVisible = false
        }

        // Show analyzed branches summary
        if (state.branches.isNotEmpty()) {
            val analyzed = state.branches.filter { it.analysisDate != null }
            val tooltip = analyzed.joinToString("\n") { b ->
                val gate = b.qualityGateStatus ?: "N/A"
                "${b.name} (${b.type}) \u2014 Gate: $gate"
            }
            branchInfoLabel.toolTipText = "<html><pre>Analyzed branches:\n$tooltip</pre></html>"
        }
    }

    private fun formatAnalysisDate(isoDate: String): String {
        return try {
            val instant = java.time.Instant.parse(isoDate)
            val zoned = instant.atZone(java.time.ZoneId.systemDefault())
            val formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            zoned.format(formatter)
        } catch (_: Exception) {
            isoDate
        }
    }

    /**
     * Sync the repo selector to the focused PR (Phase 5 T11). Drives the UI affordance
     * only - actual data refresh is handled by [renderForQualityScope] subscribed to
     * `focusQualityScope`.
     */
    private fun onFocusPrChanged(pr: PrRef) {
        if (repoSelector == null || allRepos.isEmpty()) return

        val repoIndex = allRepos.indexOfFirst { it.displayLabel == pr.repoName }
        if (repoIndex < 0) return

        suppressRepoSelectorListener = true
        repoSelector.selectedIndex = repoIndex
        suppressRepoSelectorListener = false

        if (!pr.sonarProjectKey.isNullOrBlank()) {
            qualityHintLabel.isVisible = false
            tabbedPane.isVisible = true
        } else {
            showQualityHint("SonarQube project key not configured for ${pr.repoName} \u2014 configure in Settings > CI/CD")
        }
    }

    /**
     * Refresh Sonar data when the focused quality scope changes (Phase 5 T11). Driven
     * by the `focusQualityScope` flow - fires only when the scope actually changes,
     * not on every workflow state mutation.
     */
    private fun renderForQualityScope(qualityScope: QualityScope?) {
        if (qualityScope == null) {
            // No focused PR / no Sonar key - leave panel state alone. The repoSelector
            // listener and refreshData() handle the empty-state hint already.
            return
        }
        qualityHintLabel.isVisible = false
        tabbedPane.isVisible = true
        statusLabel.text = "Switching to ${qualityScope.sonarProjectKey}..."
        loadingIcon.isVisible = true
        val branch = qualityScope.branchName ?: return
        dataService.refreshForBranch(branch, qualityScope.sonarProjectKey)
    }

    private fun showQualityHint(message: String) {
        qualityHintLabel.text = message
        qualityHintLabel.isVisible = true
        tabbedPane.isVisible = false
        loadingIcon.isVisible = false
        statusLabel.text = ""
    }

    override fun dispose() {
        scope.cancel()
    }
}

package com.workflow.orchestrator.sonar.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.settings.RepoConfig
import com.workflow.orchestrator.core.ui.StatusColors
import com.workflow.orchestrator.sonar.model.QualityGateStatus
import com.workflow.orchestrator.sonar.model.SonarState
import com.workflow.orchestrator.sonar.service.SonarDataService
import com.intellij.openapi.application.EDT
import kotlinx.coroutines.*
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.EDT)

    // Repo selector for multi-repo support
    private val sonarRepos: List<RepoConfig> = settings.getRepos().filter { !it.sonarProjectKey.isNullOrBlank() }
    private val repoSelector: ComboBox<String>? = if (sonarRepos.size > 1) {
        ComboBox(DefaultComboBoxModel(sonarRepos.map { it.displayLabel }.toTypedArray())).apply {
            val primaryIndex = sonarRepos.indexOfFirst { it.isPrimary }.takeIf { it >= 0 } ?: 0
            selectedIndex = primaryIndex
        }
    } else null

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
    private val coverageTablePanel = CoverageTablePanel(project)
    private val statusLabel = JBLabel("Loading...")
    private val loadingIcon = JBLabel(AnimatedIcon.Default()).apply { isVisible = false }

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
            add(newCodePeriodLabel)
        }

        // Top section: toolbar + branch info
        val topSection = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            val toolbarRow = JPanel(BorderLayout()).apply {
                add(headerPanel, BorderLayout.CENTER)
                add(createToolbar(), BorderLayout.EAST)
            }
            add(toolbarRow)
            add(branchInfoPanel)
        }
        add(topSection, BorderLayout.NORTH)

        // Sub-tabbed pane — track selected tab for lazy rendering
        val tabbedPane = JBTabbedPane().apply {
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
                        1 -> if (issuesStale) { issueListPanel.update(currentState.activeIssues, currentState.activeTotalIssueCount); issuesStale = false }
                        2 -> if (coverageStale) {
                            val coverageData = currentState.activeFileCoverage.ifEmpty { currentState.fileCoverage }
                            coverageTablePanel.update(coverageData, currentState.newCodeMode && currentState.activeFileCoverage.isNotEmpty(), currentState.totalCoverageFileCount)
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
            val selectedIndex = repoSelector.selectedIndex
            if (selectedIndex >= 0 && selectedIndex < sonarRepos.size) {
                val selectedRepo = sonarRepos[selectedIndex]
                val newProjectKey = selectedRepo.sonarProjectKey.orEmpty()
                if (newProjectKey.isNotBlank()) {
                    // Use refreshForProject to avoid mutating persisted settings
                    statusLabel.text = "Switching to $newProjectKey..."
                    loadingIcon.isVisible = true
                    dataService.refreshForProject(newProjectKey)
                }
            }
        }

        // Subscribe to state updates — debounce to coalesce rapid state changes
        // (e.g., branch change + PR selection firing within 500ms) into a single UI update
        @OptIn(FlowPreview::class)
        scope.launch {
            dataService.stateFlow
                .debounce(300)
                .collect { state ->
                    invokeLater { updateUI(state) }
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
        group.add(object : AnAction("Refresh", "Refresh SonarQube data", null) {
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
        statusLabel.text = "Refreshing..."
        loadingIcon.isVisible = true
        dataService.refresh()
    }

    private fun updateUI(state: SonarState) {
        if (state.projectKey.isEmpty()) {
            loadingIcon.isVisible = false
            statusLabel.text = "Configure SonarQube project key in Settings > CI/CD."
            statusLabel.foreground = JBUI.CurrentTheme.Label.disabledForeground()
            return
        }

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

        val issuesChanged = prev == null
            || prev.activeIssues != state.activeIssues
            || prev.activeTotalIssueCount != state.activeTotalIssueCount

        val coverageChanged = prev == null
            || prev.activeFileCoverage != state.activeFileCoverage
            || prev.fileCoverage != state.fileCoverage
            || prev.newCodeMode != state.newCodeMode
            || prev.totalCoverageFileCount != state.totalCoverageFileCount

        // Mark stale flags for tabs whose data changed
        if (overviewChanged) overviewStale = true
        if (issuesChanged) issuesStale = true
        if (coverageChanged) coverageStale = true

        // Only update the CURRENTLY VISIBLE sub-tab; others will update on tab switch
        when (selectedTabIndex) {
            0 -> if (overviewStale) { overviewPanel.update(state); overviewStale = false }
            1 -> if (issuesStale) { issueListPanel.update(state.activeIssues, state.activeTotalIssueCount); issuesStale = false }
            2 -> if (coverageStale) {
                val coverageData = state.activeFileCoverage.ifEmpty { state.fileCoverage }
                coverageTablePanel.update(coverageData, state.newCodeMode && state.activeFileCoverage.isNotEmpty(), state.totalCoverageFileCount)
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

    override fun dispose() {
        scope.cancel()
    }
}

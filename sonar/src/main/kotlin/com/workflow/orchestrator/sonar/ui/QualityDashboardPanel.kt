package com.workflow.orchestrator.sonar.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.project.Project
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.core.ui.StatusColors
import com.workflow.orchestrator.sonar.model.QualityGateStatus
import com.workflow.orchestrator.sonar.model.SonarState
import com.workflow.orchestrator.sonar.service.SonarDataService
import com.intellij.openapi.application.EDT
import kotlinx.coroutines.*
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel

class QualityDashboardPanel(
    private val project: Project
) : JPanel(BorderLayout()), Disposable {

    private val dataService = SonarDataService.getInstance(project)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.EDT)

    // UI components
    private val headerLabel = JBLabel("").apply {
        font = font.deriveFont(Font.PLAIN, 11f)
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
        val headerPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), JBUI.scale(2))).apply {
            border = JBUI.Borders.empty(4, 8)
            add(headerLabel)
            add(newCodeButton)
            add(overallButton)
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

        // Sub-tabbed pane
        val tabbedPane = JBTabbedPane().apply {
            addTab("Overview", overviewPanel)
            addTab("Issues", issueListPanel)
            addTab("Coverage", coverageTablePanel)
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

        // Subscribe to state updates
        scope.launch {
            dataService.stateFlow.collect { state ->
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

        val gateIcon = when (state.qualityGate.status) {
            QualityGateStatus.PASSED -> "\u2713"
            QualityGateStatus.FAILED -> "\u2717"
            QualityGateStatus.NONE -> "—"
        }

        val modeLabel = if (state.newCodeMode) "New Code" else "Overall"
        val coverage = state.activeOverallCoverage
        val issueCount = state.activeIssues.size
        headerLabel.text = "${state.projectKey}  $gateIcon $modeLabel  \u2022  Coverage: ${"%.1f".format(coverage.lineCoverage)}%  \u2022  Issues: $issueCount"

        updateToggleAppearance(state.newCodeMode)

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

        overviewPanel.update(state)
        issueListPanel.update(state.activeIssues, state.activeTotalIssueCount)
        val coverageData = state.activeFileCoverage.ifEmpty { state.fileCoverage }
        coverageTablePanel.update(
            coverageData,
            state.newCodeMode && state.activeFileCoverage.isNotEmpty(),
            state.totalCoverageFileCount
        )

        loadingIcon.isVisible = false
        val elapsed = java.time.Duration.between(state.lastUpdated, java.time.Instant.now())
        statusLabel.text = "Updated ${elapsed.seconds}s ago"
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

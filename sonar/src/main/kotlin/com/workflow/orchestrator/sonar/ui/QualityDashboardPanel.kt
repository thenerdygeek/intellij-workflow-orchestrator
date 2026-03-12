package com.workflow.orchestrator.sonar.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.sonar.model.QualityGateStatus
import com.workflow.orchestrator.sonar.model.SonarState
import com.workflow.orchestrator.sonar.service.SonarDataService
import kotlinx.coroutines.*
import java.awt.BorderLayout
import java.awt.Font
import javax.swing.JPanel

class QualityDashboardPanel(
    private val project: Project
) : JPanel(BorderLayout()), Disposable {

    private val dataService = SonarDataService.getInstance(project)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // UI components
    private val headerLabel = JBLabel("").apply {
        font = font.deriveFont(Font.PLAIN, 11f)
        border = JBUI.Borders.empty(4, 8)
    }
    private val overviewPanel = OverviewPanel(project)
    private val issueListPanel = IssueListPanel(project)
    private val coverageTablePanel = CoverageTablePanel(project)
    private val statusLabel = JBLabel("Loading...")

    init {
        border = JBUI.Borders.empty()

        // Toolbar
        add(createToolbar(), BorderLayout.NORTH)

        // Sub-tabbed pane
        val tabbedPane = JBTabbedPane().apply {
            addTab("Overview", overviewPanel)
            addTab("Issues", issueListPanel)
            addTab("Coverage", coverageTablePanel)
        }
        add(tabbedPane, BorderLayout.CENTER)

        // Status bar
        val statusPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(2, 8)
            add(statusLabel, BorderLayout.WEST)
        }
        add(statusPanel, BorderLayout.SOUTH)

        // Subscribe to state updates
        scope.launch {
            dataService.stateFlow.collect { state ->
                invokeLater { updateUI(state) }
            }
        }

        // Initial refresh
        refreshData()
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
        dataService.refresh()
    }

    private fun updateUI(state: SonarState) {
        if (state.projectKey.isEmpty()) return

        val gateIcon = when (state.qualityGate.status) {
            QualityGateStatus.PASSED -> "\u2713"
            QualityGateStatus.FAILED -> "\u2717"
            QualityGateStatus.NONE -> "—"
        }
        headerLabel.text = "${state.projectKey} [$gateIcon] Coverage: ${"%.1f".format(state.overallCoverage.lineCoverage)}% Issues: ${state.issues.size}"

        overviewPanel.update(state)
        issueListPanel.update(state.issues)
        coverageTablePanel.update(state.fileCoverage)

        val elapsed = java.time.Duration.between(state.lastUpdated, java.time.Instant.now())
        statusLabel.text = "Updated ${elapsed.seconds}s ago"
    }

    override fun dispose() {
        scope.cancel()
    }
}

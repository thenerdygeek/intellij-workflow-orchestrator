package com.workflow.orchestrator.sonar.ui

import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.sonar.model.*
import java.awt.*
import javax.swing.*

class OverviewPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val gateStatusLabel = JBLabel("—")
    private val gateConditionsPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
    }
    private val coverageLabel = JBLabel("—")
    private val branchCoverageLabel = JBLabel("—")
    private val coverageBar = CoverageProgressBar()
    private val issueCountLabel = JBLabel("—")
    private val issueBreakdownLabel = JBLabel("")
    private val recentIssuesPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
    }

    init {
        border = JBUI.Borders.empty(12)

        val cardsPanel = JPanel(GridLayout(1, 3, 12, 0))

        // Quality Gate card
        val gateCard = createCard("QUALITY GATE", gateStatusLabel, gateConditionsPanel)
        cardsPanel.add(gateCard)

        // Coverage card
        val coverageCard = createCard("COVERAGE", coverageLabel, JPanel(BorderLayout()).apply {
            isOpaque = false
            add(coverageBar, BorderLayout.NORTH)
            add(branchCoverageLabel, BorderLayout.CENTER)
        })
        cardsPanel.add(coverageCard)

        // Issues card
        val issuesCard = createCard("ISSUES", issueCountLabel, issueBreakdownLabel)
        cardsPanel.add(issuesCard)

        add(cardsPanel, BorderLayout.NORTH)

        // Recent issues section
        val recentSection = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.emptyTop(16)
            isOpaque = false
            val header = JBLabel("RECENT ISSUES").apply {
                foreground = JBColor.GRAY
                font = font.deriveFont(Font.PLAIN, 10f)
            }
            add(header, BorderLayout.NORTH)
            add(JBScrollPane(recentIssuesPanel).apply {
                border = JBUI.Borders.emptyTop(4)
            }, BorderLayout.CENTER)
        }
        add(recentSection, BorderLayout.CENTER)
    }

    fun update(state: SonarState) {
        val settings = PluginSettings.getInstance(project).state
        val highThreshold = settings.coverageHighThreshold.toDouble()
        val mediumThreshold = settings.coverageMediumThreshold.toDouble()

        // Quality gate
        val (gateText, gateColor) = when (state.qualityGate.status) {
            QualityGateStatus.PASSED -> "\u2713 PASSED" to CoverageThresholds.GREEN
            QualityGateStatus.FAILED -> "\u2717 FAILED" to CoverageThresholds.RED
            QualityGateStatus.NONE -> "—" to JBColor.GRAY
        }
        gateStatusLabel.text = gateText
        gateStatusLabel.foreground = gateColor
        gateStatusLabel.font = gateStatusLabel.font.deriveFont(Font.BOLD, 18f)

        gateConditionsPanel.removeAll()
        state.qualityGate.conditions.forEach { cond ->
            val icon = if (cond.passed) "\u2713" else "\u2717"
            val label = JBLabel("$icon ${cond.metric}: ${cond.actualValue} (threshold: ${cond.threshold})")
            label.font = label.font.deriveFont(10f)
            label.foreground = if (cond.passed) JBColor.GRAY else CoverageThresholds.RED
            gateConditionsPanel.add(label)
        }

        // Coverage
        val lineCov = state.overallCoverage.lineCoverage
        coverageLabel.text = "%.1f%%".format(lineCov)
        coverageLabel.font = coverageLabel.font.deriveFont(Font.BOLD, 18f)
        coverageLabel.foreground = CoverageThresholds.colorForCoverage(lineCov, highThreshold, mediumThreshold)
        coverageBar.setThresholds(highThreshold, mediumThreshold)
        coverageBar.value = lineCov
        branchCoverageLabel.text = "Branch: %.1f%%".format(state.overallCoverage.branchCoverage)
        branchCoverageLabel.foreground = JBColor.GRAY
        branchCoverageLabel.font = branchCoverageLabel.font.deriveFont(10f)

        // Issues
        val total = state.issues.size
        issueCountLabel.text = "$total"
        issueCountLabel.font = issueCountLabel.font.deriveFont(Font.BOLD, 18f)
        val bugs = state.issues.count { it.type == IssueType.BUG }
        val vulns = state.issues.count { it.type == IssueType.VULNERABILITY }
        val smells = state.issues.count { it.type == IssueType.CODE_SMELL }
        val hotspots = state.issues.count { it.type == IssueType.SECURITY_HOTSPOT }
        issueBreakdownLabel.text = "<html>${bugs}B ${vulns}V ${smells}S ${hotspots}H</html>"
        issueBreakdownLabel.font = issueBreakdownLabel.font.deriveFont(10f)

        // Recent issues (top 5 by severity)
        recentIssuesPanel.removeAll()
        state.issues
            .sortedBy { it.severity.ordinal }
            .take(5)
            .forEach { issue ->
                val color = severityColor(issue.severity)
                val label = JBLabel("<html><font color='${htmlColor(color)}'>\u25CF</font> " +
                    "${issue.type} <font color='${htmlColor(color)}'>${issue.severity}</font> " +
                    "${issue.message} — ${java.io.File(issue.filePath).name}:${issue.startLine}</html>")
                label.font = label.font.deriveFont(11f)
                label.border = JBUI.Borders.emptyBottom(2)
                recentIssuesPanel.add(label)
            }

        revalidate()
        repaint()
    }

    private fun createCard(title: String, mainContent: JComponent, subContent: JComponent): JPanel {
        return JPanel(BorderLayout()).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JBColor.border(), 1),
                JBUI.Borders.empty(10)
            )
            val titleLabel = JBLabel(title).apply {
                foreground = JBColor.GRAY
                font = font.deriveFont(Font.PLAIN, 9f)
            }
            add(titleLabel, BorderLayout.NORTH)
            add(mainContent, BorderLayout.CENTER)
            add(subContent, BorderLayout.SOUTH)
        }
    }

    private fun severityColor(severity: IssueSeverity): Color = when (severity) {
        IssueSeverity.BLOCKER, IssueSeverity.CRITICAL -> Color(255, 68, 68)
        IssueSeverity.MAJOR -> Color(230, 138, 0)
        IssueSeverity.MINOR -> Color(255, 170, 0)
        IssueSeverity.INFO -> Color(136, 136, 136)
    }

    private fun htmlColor(c: Color) = "#%02x%02x%02x".format(c.red, c.green, c.blue)
}

private class CoverageProgressBar : JPanel() {
    var value: Double = 0.0
        set(v) { field = v; repaint() }

    private var highThreshold: Double = 80.0
    private var mediumThreshold: Double = 50.0

    fun setThresholds(high: Double, medium: Double) {
        highThreshold = high
        mediumThreshold = medium
        repaint()
    }

    init {
        preferredSize = Dimension(0, 6)
        minimumSize = Dimension(0, 6)
        isOpaque = false
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = JBColor(Color(30, 30, 30), Color(30, 30, 30))
        g2.fillRoundRect(0, 0, width, height, 4, 4)
        val fillWidth = (width * value / 100.0).toInt()
        g2.color = CoverageThresholds.colorForCoverage(value, highThreshold, mediumThreshold)
        g2.fillRoundRect(0, 0, fillWidth, height, 4, 4)
    }
}

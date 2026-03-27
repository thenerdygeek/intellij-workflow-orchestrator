package com.workflow.orchestrator.sonar.ui

import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.ui.StatusColors
import com.workflow.orchestrator.sonar.model.*
import com.intellij.util.ui.JBFont
import java.awt.*
import javax.swing.*

class OverviewPanel(private val project: Project) : JPanel(BorderLayout()) {

    companion object {
        // Cached fonts — avoid Font.deriveFont() per update (expensive GDI calls on Windows)
        private val FONT_BOLD_18 by lazy { JBFont.regular().deriveFont(Font.BOLD, JBUI.scale(18).toFloat()) }
        private val FONT_PLAIN_10 by lazy { JBFont.regular().deriveFont(Font.PLAIN, JBUI.scale(10).toFloat()) }
        private val FONT_PLAIN_11 by lazy { JBFont.regular().deriveFont(Font.PLAIN, JBUI.scale(11).toFloat()) }
        private val FONT_BOLD_10 by lazy { JBFont.regular().deriveFont(Font.BOLD, JBUI.scale(10).toFloat()) }

        // Accent colors for card left borders (Stitch design)
        private val ACCENT_GATE = StatusColors.LINK
        private val ACCENT_COVERAGE = StatusColors.SUCCESS
        private val ACCENT_ISSUES = StatusColors.WARNING
        private val ACCENT_HEALTH = StatusColors.MERGED
    }

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

    // Project Health card components
    private val healthRatingLabel = JBLabel("—")
    private val healthDetailsPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
    }

    init {
        border = JBUI.Borders.empty(12)

        val cardsPanel = JPanel(GridLayout(2, 2, 12, 12)).apply {
            minimumSize = Dimension(JBUI.scale(450), JBUI.scale(200))
            preferredSize = Dimension(JBUI.scale(600), JBUI.scale(200))
        }

        // Quality Gate card
        val gateCard = createCard("QUALITY GATE", gateStatusLabel, gateConditionsPanel, ACCENT_GATE)
        cardsPanel.add(gateCard)

        // Coverage card
        val coverageCard = createCard("COVERAGE", coverageLabel, JPanel(BorderLayout()).apply {
            isOpaque = false
            add(coverageBar, BorderLayout.NORTH)
            add(branchCoverageLabel, BorderLayout.CENTER)
        }, ACCENT_COVERAGE)
        cardsPanel.add(coverageCard)

        // Issues card
        val issuesCard = createCard("ISSUES", issueCountLabel, issueBreakdownLabel, ACCENT_ISSUES)
        cardsPanel.add(issuesCard)

        // Project Health card
        val healthCard = createCard("PROJECT HEALTH", healthRatingLabel, healthDetailsPanel, ACCENT_HEALTH)
        cardsPanel.add(healthCard)

        // Wrap in scroll pane for narrow tool windows
        val cardsScrollPane = JBScrollPane(
            cardsPanel,
            JBScrollPane.VERTICAL_SCROLLBAR_NEVER,
            JBScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
        ).apply {
            border = JBUI.Borders.empty()
            isOpaque = false
            viewport.isOpaque = false
        }
        add(cardsScrollPane, BorderLayout.NORTH)

        // Recent issues section
        val recentSection = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.emptyTop(16)
            isOpaque = false
            val header = JBLabel("RECENT ISSUES").apply {
                foreground = StatusColors.SECONDARY_TEXT
                font = FONT_BOLD_10
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
            QualityGateStatus.PASSED -> "\u2713 PASSED" to StatusColors.SUCCESS
            QualityGateStatus.FAILED -> "\u2717 FAILED" to StatusColors.ERROR
            QualityGateStatus.NONE -> "—" to JBColor.GRAY
        }
        gateStatusLabel.text = gateText
        gateStatusLabel.foreground = gateColor
        gateStatusLabel.font = FONT_BOLD_18
        gateStatusLabel.isOpaque = true
        gateStatusLabel.background = when (state.qualityGate.status) {
            QualityGateStatus.PASSED -> StatusColors.SUCCESS_BG
            QualityGateStatus.FAILED -> JBColor(Color(0xFD, 0xE7, 0xE9), Color(0x4A, 0x1A, 0x1A))
            QualityGateStatus.NONE -> JBColor(Color(0xF5, 0xF5, 0xF5), Color(0x35, 0x35, 0x35))
        }
        gateStatusLabel.border = JBUI.Borders.empty(2, 6)

        gateConditionsPanel.removeAll()
        state.qualityGate.conditions.forEach { cond ->
            val icon = if (cond.passed) "\u2713" else "\u2717"
            val metricName = cond.metric.replace("_", " ")
                .split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
            val isCoverageMetric = cond.metric.contains("coverage", ignoreCase = true)
            val suffix = if (isCoverageMetric) "%" else ""
            val label = JBLabel("$icon $metricName: ${cond.actualValue}$suffix (threshold: ${cond.threshold}$suffix)")
            label.font = FONT_PLAIN_10
            // Determine condition color: green (passed), yellow (warning zone), red (failed)
            label.foreground = when {
                cond.passed -> {
                    // Check if in warning zone (between warning threshold and error threshold)
                    val inWarningZone = cond.warningThreshold?.let { wt ->
                        isInWarningZone(cond.actualValue, wt, cond.threshold, cond.comparator)
                    } ?: false
                    if (inWarningZone) StatusColors.WARNING else JBColor.GRAY
                }
                else -> StatusColors.ERROR
            }
            gateConditionsPanel.add(label)
        }

        // Coverage
        val lineCov = state.activeOverallCoverage.lineCoverage
        coverageLabel.text = "%.1f%%".format(lineCov)
        coverageLabel.font = FONT_BOLD_18
        coverageLabel.foreground = CoverageThresholds.colorForCoverage(lineCov, highThreshold, mediumThreshold)
        coverageBar.setThresholds(highThreshold, mediumThreshold)
        coverageBar.value = lineCov
        branchCoverageLabel.text = "Branch: %.1f%%".format(state.activeOverallCoverage.branchCoverage)
        branchCoverageLabel.foreground = JBColor.GRAY
        branchCoverageLabel.font = FONT_PLAIN_10

        // Issues
        val total = state.activeIssues.size
        issueCountLabel.text = "$total"
        issueCountLabel.font = FONT_BOLD_18
        val bugs = state.activeIssues.count { it.type == IssueType.BUG }
        val vulns = state.activeIssues.count { it.type == IssueType.VULNERABILITY }
        val smells = state.activeIssues.count { it.type == IssueType.CODE_SMELL }
        val hotspots = state.activeIssues.count { it.type == IssueType.SECURITY_HOTSPOT }
        // Calculate total effort from all active issues
        val totalEffortMinutes = state.activeIssues.mapNotNull { it.effort?.let { e -> parseEffortToMinutes(e) } }.sum()
        val effortText = formatEffortMinutes(totalEffortMinutes)

        issueBreakdownLabel.text = "<html>${bugs}B ${vulns}V ${smells}S ${hotspots}H<br/><font color='${StatusColors.htmlColor(StatusColors.SECONDARY_TEXT)}'>Total effort: $effortText</font></html>"
        issueBreakdownLabel.font = FONT_PLAIN_10

        // Recent issues (top 5 by severity)
        recentIssuesPanel.removeAll()
        state.activeIssues
            .sortedBy { it.severity.ordinal }
            .take(5)
            .forEach { issue ->
                val color = severityColor(issue.severity)
                val label = JBLabel("<html><font color='${htmlColor(color)}'>\u25CF</font> " +
                    "${issue.type} <font color='${htmlColor(color)}'>${issue.severity}</font> " +
                    "${issue.message} — ${java.io.File(issue.filePath).name}:${issue.startLine}</html>")
                label.font = FONT_PLAIN_11
                label.border = JBUI.Borders.emptyBottom(2)
                recentIssuesPanel.add(label)
            }

        // Project Health
        val health = state.projectHealth
        if (health.maintainabilityRating.isNotEmpty()) {
            healthRatingLabel.text = health.maintainabilityRating
            healthRatingLabel.font = FONT_BOLD_18
            healthRatingLabel.foreground = ratingColor(health.maintainabilityRating)
        } else {
            healthRatingLabel.text = "—"
            healthRatingLabel.foreground = JBColor.GRAY
            healthRatingLabel.font = FONT_BOLD_18
        }

        healthDetailsPanel.removeAll()
        if (health.technicalDebtMinutes > 0 || health.maintainabilityRating.isNotEmpty()) {
            val debtLabel = JBLabel("Debt: ${health.formattedDebt}")
            debtLabel.font = FONT_PLAIN_10
            debtLabel.foreground = JBColor.GRAY
            healthDetailsPanel.add(debtLabel)

            val ratingsLabel = JBLabel("<html>" +
                "Reliability: <font color='${htmlColor(ratingColor(health.reliabilityRating))}'>${health.reliabilityRating.ifEmpty { "—" }}</font> " +
                "Security: <font color='${htmlColor(ratingColor(health.securityRating))}'>${health.securityRating.ifEmpty { "—" }}</font>" +
                "</html>")
            ratingsLabel.font = FONT_PLAIN_10
            healthDetailsPanel.add(ratingsLabel)

            val dupLabel = JBLabel("Duplication: %.1f%%".format(health.duplicatedLinesDensity))
            dupLabel.font = FONT_PLAIN_10
            dupLabel.foreground = JBColor.GRAY
            healthDetailsPanel.add(dupLabel)

            if (health.cognitiveComplexity > 0) {
                val complexityLabel = JBLabel("Cognitive Complexity: ${health.cognitiveComplexity}")
                complexityLabel.font = FONT_PLAIN_10
                complexityLabel.foreground = JBColor.GRAY
                healthDetailsPanel.add(complexityLabel)
            }
        }

        revalidate()
        repaint()
    }

    /** Color for SonarQube A-E rating: A/B=green, C=yellow, D=orange, E=red. */
    private fun ratingColor(rating: String): Color = when (rating) {
        "A", "B" -> StatusColors.SUCCESS
        "C" -> StatusColors.WARNING
        "D" -> StatusColors.WARNING // orange
        "E" -> StatusColors.ERROR
        else -> JBColor.GRAY
    }

    /**
     * Check if the actual value is in the warning zone: past the warning threshold
     * but not yet past the error threshold. Handles LT (less than) and GT (greater than).
     */
    private fun isInWarningZone(actualStr: String, warningStr: String, errorStr: String, comparator: String): Boolean {
        val actual = actualStr.toDoubleOrNull() ?: return false
        val warning = warningStr.toDoubleOrNull() ?: return false
        val error = errorStr.toDoubleOrNull() ?: return false
        return when (comparator) {
            "LT" -> actual < warning && actual >= error   // e.g., coverage: warn < 90, error < 80
            "GT" -> actual > warning && actual <= error    // e.g., bugs: warn > 0, error > 5
            else -> false
        }
    }

    private fun createCard(title: String, mainContent: JComponent, subContent: JComponent, accentColor: JBColor): JPanel {
        return JPanel(BorderLayout()).apply {
            // Tonal background instead of line border (Stitch design)
            background = StatusColors.CARD_BG
            isOpaque = true
            // 2px left accent border + inner padding
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 2, 0, 0, accentColor),
                JBUI.Borders.empty(10)
            )
            val titleLabel = JBLabel(title).apply {
                foreground = StatusColors.SECONDARY_TEXT
                font = FONT_BOLD_10
            }
            add(titleLabel, BorderLayout.NORTH)
            add(mainContent, BorderLayout.CENTER)
            add(subContent, BorderLayout.SOUTH)
        }
    }

    private fun severityColor(severity: IssueSeverity): Color = when (severity) {
        IssueSeverity.BLOCKER, IssueSeverity.CRITICAL -> StatusColors.ERROR
        IssueSeverity.MAJOR -> StatusColors.WARNING
        IssueSeverity.MINOR -> StatusColors.WARNING
        IssueSeverity.INFO -> StatusColors.INFO
    }

    private fun htmlColor(c: Color): String {
        return if (c is JBColor) StatusColors.htmlColor(c) else String.format("#%02x%02x%02x", c.red, c.green, c.blue)
    }

    /**
     * Parse SonarQube effort string (e.g., "30min", "2h", "1h30min", "3d") to total minutes.
     */
    private fun parseEffortToMinutes(effort: String): Int {
        var total = 0
        val dayMatch = Regex("(\\d+)d").find(effort)
        val hourMatch = Regex("(\\d+)h").find(effort)
        val minMatch = Regex("(\\d+)min").find(effort)
        dayMatch?.let { total += it.groupValues[1].toInt() * 8 * 60 } // 8h workday
        hourMatch?.let { total += it.groupValues[1].toInt() * 60 }
        minMatch?.let { total += it.groupValues[1].toInt() }
        return total
    }

    /**
     * Format total minutes as human-readable duration (e.g., "4h 30min", "2d 3h").
     */
    private fun formatEffortMinutes(totalMinutes: Int): String {
        if (totalMinutes <= 0) return "0min"
        val days = totalMinutes / (8 * 60)
        val hours = (totalMinutes % (8 * 60)) / 60
        val mins = totalMinutes % 60
        return buildString {
            if (days > 0) append("${days}d ")
            if (hours > 0) append("${hours}h ")
            if (mins > 0 && days == 0) append("${mins}min")
        }.trim().ifEmpty { "0min" }
    }
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
        g2.color = JBColor(Color(0xE0, 0xE0, 0xE0), Color(0x3C, 0x3C, 0x3C))
        g2.fillRoundRect(0, 0, width, height, 2, 2)
        val fillWidth = (width * value / 100.0).toInt()
        g2.color = CoverageThresholds.colorForCoverage(value, highThreshold, mediumThreshold)
        g2.fillRoundRect(0, 0, fillWidth, height, 2, 2)
    }
}

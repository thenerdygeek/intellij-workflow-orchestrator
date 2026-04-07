package com.workflow.orchestrator.sonar.ui

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.core.ui.StatusColors
import com.workflow.orchestrator.sonar.model.*
import java.awt.*
import javax.swing.JPanel

/**
 * Full-width banner shown when the quality gate is FAILED.
 * Displays failing conditions and a clickable link to navigate to blocking issues.
 */
class GateStatusBanner : JPanel(BorderLayout()) {

    /** Callback invoked when "Show Blocking Issues" is clicked. */
    var onShowBlockingIssues: ((FilterAction) -> Unit)? = null

    private val iconLabel = JBLabel("\u26A0").apply {
        font = font.deriveFont(Font.BOLD, JBUI.scale(13).toFloat())
        foreground = StatusColors.ERROR
    }

    private val titleLabel = JBLabel("Quality Gate Failed").apply {
        font = font.deriveFont(Font.BOLD, JBUI.scale(12).toFloat())
        foreground = StatusColors.ERROR
    }

    private val conditionsLabel = JBLabel("").apply {
        font = font.deriveFont(Font.PLAIN, JBUI.scale(11).toFloat())
        foreground = JBColor.foreground()
    }

    private val showIssuesLink = JBLabel("Show Blocking Issues").apply {
        font = font.deriveFont(Font.PLAIN, JBUI.scale(11).toFloat())
        foreground = StatusColors.LINK
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    }

    private var currentConditions: List<GateCondition> = emptyList()

    init {
        isOpaque = true
        background = JBColor(Color(0xFD, 0xE7, 0xE9), Color(0x4A, 0x1A, 0x1A))
        border = JBUI.Borders.empty(6, 10)
        isVisible = false

        // Left section: icon + title
        val leftPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
            isOpaque = false
            add(iconLabel)
            add(titleLabel)
        }

        // Center: conditions text
        val centerPanel = JPanel(FlowLayout(FlowLayout.CENTER, 0, 0)).apply {
            isOpaque = false
            add(conditionsLabel)
        }

        // Right: clickable link
        val rightPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
            isOpaque = false
            add(showIssuesLink)
        }

        add(leftPanel, BorderLayout.WEST)
        add(centerPanel, BorderLayout.CENTER)
        add(rightPanel, BorderLayout.EAST)

        showIssuesLink.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                val firstFailingIssueCondition = currentConditions
                    .asSequence()
                    .filter { !it.passed }
                    .mapNotNull { mapConditionToFilter(it) }
                    .firstOrNull()
                if (firstFailingIssueCondition != null) {
                    onShowBlockingIssues?.invoke(firstFailingIssueCondition)
                }
            }
        })
    }

    /**
     * Updates the banner visibility and content based on the quality gate state.
     * Shows the banner only when the gate status is FAILED.
     */
    fun update(gateState: QualityGateState) {
        if (gateState.status == QualityGateStatus.FAILED) {
            currentConditions = gateState.conditions
            conditionsLabel.text = formatFailingConditions(gateState.conditions)
            isVisible = true
        } else {
            isVisible = false
        }
        revalidate()
        repaint()
    }

    companion object {
        /**
         * Maps a gate condition metric to a [FilterAction] for navigating to the relevant issues.
         * Returns null for passing conditions.
         */
        fun mapConditionToFilter(condition: GateCondition): FilterAction? {
            if (condition.passed) return null

            val metric = condition.metric.lowercase()
            val newCodeMode = metric.startsWith("new_")

            val issueType = when {
                metric.contains("bug") -> IssueType.BUG
                metric.contains("vulnerabilit") -> IssueType.VULNERABILITY
                metric.contains("hotspot") -> IssueType.SECURITY_HOTSPOT
                else -> null
            }

            val isCoverageCondition = issueType == null &&
                (metric.contains("coverage") || metric.contains("duplicat"))

            // If we couldn't determine an issue type and it's not a coverage condition, still return a filter
            return FilterAction(
                issueType = issueType,
                newCodeMode = newCodeMode,
                isCoverageCondition = isCoverageCondition
            )
        }

        /**
         * Formats only the failing conditions into a human-readable string.
         * Passing conditions are excluded.
         */
        fun formatFailingConditions(conditions: List<GateCondition>): String {
            return conditions
                .filter { !it.passed }
                .joinToString(" | ") { cond ->
                    val metricName = cond.metric.replace("_", " ")
                        .split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
                    val isCoverage = cond.metric.contains("coverage", ignoreCase = true) ||
                        cond.metric.contains("duplicat", ignoreCase = true)
                    val suffix = if (isCoverage) "%" else ""
                    "$metricName: ${cond.actualValue}$suffix (threshold: ${cond.threshold}$suffix)"
                }
        }
    }
}

/**
 * Represents a filter action to apply when navigating from the gate banner to issue/coverage tabs.
 */
data class FilterAction(
    val issueType: IssueType? = null,
    val newCodeMode: Boolean = false,
    val isCoverageCondition: Boolean = false
)

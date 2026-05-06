package com.workflow.orchestrator.sonar.ui

import com.intellij.ui.JBColor
import com.workflow.orchestrator.core.ui.StatusColors
import com.workflow.orchestrator.sonar.model.Impact
import com.workflow.orchestrator.sonar.model.ImpactSeverity
import com.workflow.orchestrator.sonar.model.MappedIssue
import com.workflow.orchestrator.sonar.model.SoftwareQuality

/**
 * Rendering helpers for the SonarQube 9.6+ Clean Code taxonomy. Used by both
 * the issue list cell renderer and the detail panel so badge text/colors stay
 * consistent.
 *
 * Older Sonar versions (< 9.6) leave [MappedIssue.impacts] empty; every helper
 * returns an empty string in that case so callers do not need to null-check.
 */
internal object ImpactRendering {

    /** 3-letter abbreviation used in the compact list-row badge. */
    fun shortName(quality: SoftwareQuality): String = when (quality) {
        SoftwareQuality.RELIABILITY -> "REL"
        SoftwareQuality.SECURITY -> "SEC"
        SoftwareQuality.MAINTAINABILITY -> "MNT"
        SoftwareQuality.UNKNOWN -> "?"
    }

    /** Severity rank — higher value means more urgent. UNKNOWN ranks lowest. */
    private fun rank(severity: ImpactSeverity): Int = when (severity) {
        ImpactSeverity.BLOCKER -> 5
        ImpactSeverity.HIGH -> 4
        ImpactSeverity.MEDIUM -> 3
        ImpactSeverity.LOW -> 2
        ImpactSeverity.INFO -> 1
        ImpactSeverity.UNKNOWN -> 0
    }

    /** Pick the impact whose [Impact.severity] is highest. Null when [impacts] is empty. */
    fun highest(impacts: List<Impact>): Impact? =
        impacts.maxByOrNull { rank(it.severity) }

    /** JBColor matching an impact severity. */
    fun colorFor(severity: ImpactSeverity): JBColor = when (severity) {
        ImpactSeverity.BLOCKER, ImpactSeverity.HIGH -> StatusColors.ERROR
        ImpactSeverity.MEDIUM -> StatusColors.WARNING
        ImpactSeverity.LOW, ImpactSeverity.INFO -> StatusColors.INFO
        ImpactSeverity.UNKNOWN -> StatusColors.SECONDARY_TEXT
    } as JBColor

    /**
     * Compact badge for the issue list row, e.g. ` <font color='#cc4444'><b>[REL/HIGH]</b></font>`.
     * Includes a leading space so callers can string-concat without managing whitespace.
     * Empty when [issue] has no impacts.
     */
    fun htmlBadge(issue: MappedIssue): String {
        val top = highest(issue.impacts) ?: return ""
        val color = StatusColors.htmlColor(colorFor(top.severity))
        return " <font color='$color'><b>[${shortName(top.softwareQuality)}/${top.severity.name}]</b></font>"
    }
}

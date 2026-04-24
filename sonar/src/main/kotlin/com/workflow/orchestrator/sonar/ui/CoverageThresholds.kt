package com.workflow.orchestrator.sonar.ui

import com.intellij.ui.JBColor
import com.workflow.orchestrator.core.ui.StatusColors

object CoverageThresholds {
    fun colorForCoverage(pct: Double, highThreshold: Double, mediumThreshold: Double): JBColor {
        return when {
            pct >= highThreshold -> StatusColors.SUCCESS
            pct >= mediumThreshold -> StatusColors.WARNING
            else -> StatusColors.ERROR
        }
    }
}

package com.workflow.orchestrator.sonar.ui

import com.intellij.ui.JBColor
import java.awt.Color

object CoverageThresholds {
    val GREEN = JBColor(Color(46, 160, 67), Color(46, 160, 67))
    val YELLOW = JBColor(Color(212, 160, 32), Color(212, 160, 32))
    val RED = JBColor(Color(255, 68, 68), Color(255, 68, 68))

    fun colorForCoverage(pct: Double, highThreshold: Double, mediumThreshold: Double): JBColor {
        return when {
            pct >= highThreshold -> GREEN
            pct >= mediumThreshold -> YELLOW
            else -> RED
        }
    }
}

package com.workflow.orchestrator.sonar.ui

import com.intellij.ui.JBColor
import java.awt.Color

object CoverageThresholds {
    val GREEN = JBColor(Color(0x1B, 0x7F, 0x37), Color(0x3F, 0xB9, 0x50))   // matches StatusColors.SUCCESS
    val YELLOW = JBColor(Color(0xBF, 0x80, 0x00), Color(0xE3, 0xB3, 0x41))  // matches StatusColors.WARNING
    val RED = JBColor(Color(0xCF, 0x22, 0x2E), Color(0xF8, 0x5E, 0x5E))    // matches StatusColors.ERROR

    fun colorForCoverage(pct: Double, highThreshold: Double, mediumThreshold: Double): JBColor {
        return when {
            pct >= highThreshold -> GREEN
            pct >= mediumThreshold -> YELLOW
            else -> RED
        }
    }
}

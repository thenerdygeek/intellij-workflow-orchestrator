package com.workflow.orchestrator.core.ui

import com.intellij.ui.JBColor
import java.awt.Color

/**
 * Unified status colors used across all tabs.
 * Light/dark theme variants for consistent appearance.
 */
object StatusColors {
    // Success / Passed / Done
    val SUCCESS = JBColor(Color(0x1B, 0x7F, 0x37), Color(0x3F, 0xB9, 0x50))
    // Error / Failed / Critical
    val ERROR = JBColor(Color(0xCF, 0x22, 0x2E), Color(0xF8, 0x5E, 0x5E))
    // Warning / Major / In Progress
    val WARNING = JBColor(Color(0xBF, 0x80, 0x00), Color(0xE3, 0xB3, 0x41))
    // Info / Minor
    val INFO = JBColor(Color(0x57, 0x60, 0x6A), Color(0x8B, 0x94, 0x9E))
    // Link / Action
    val LINK = JBColor(Color(0x1A, 0x73, 0xE8), Color(0x8A, 0xB4, 0xF8))
    // Open / Active
    val OPEN = SUCCESS
    // Merged / Complete
    val MERGED = JBColor(Color(0x6F, 0x42, 0xC1), Color(0xB8, 0x7B, 0xFF))
    // Declined / Closed
    val DECLINED = ERROR
    // Secondary text / Dimmed
    val SECONDARY_TEXT = JBColor(Color(0x65, 0x6D, 0x76), Color(0x8B, 0x94, 0x9E))
}

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
    // Border / Separator
    val BORDER = JBColor(Color(0xD1, 0xD9, 0xE0), Color(0x44, 0x4D, 0x56))
    // Card background
    val CARD_BG = JBColor(Color(0xF7, 0xF8, 0xFA), Color(0x2B, 0x2D, 0x30))
    // Highlight background
    val HIGHLIGHT_BG = JBColor(Color(0xDE, 0xE9, 0xFC), Color(0x2D, 0x35, 0x48))
    // Warning background
    val WARNING_BG = JBColor(Color(0xFF, 0xF3, 0xE0), Color(0x4E, 0x34, 0x2E))
    // Success background
    val SUCCESS_BG = JBColor(Color(0xE8, 0xF5, 0xE9), Color(0x1A, 0x3D, 0x1A))
    // Info background
    val INFO_BG = JBColor(Color(0xE3, 0xF2, 0xFD), Color(0x1E, 0x3A, 0x5F))

    /**
     * Returns the current theme's resolved hex color string for use in HTML.
     * Must be called at render time — never cache the result across theme changes.
     */
    fun htmlColor(color: JBColor): String {
        val c = color as Color
        return String.format("#%02x%02x%02x", c.red, c.green, c.blue)
    }
}

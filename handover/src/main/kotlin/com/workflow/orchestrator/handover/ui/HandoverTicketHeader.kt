package com.workflow.orchestrator.handover.ui

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.core.ui.StatusColors
import com.workflow.orchestrator.handover.model.HandoverState
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * Compact one-row header widget for the Handover tab showing the active ticket.
 *
 * Layout (BorderLayout):
 *   WEST  — ticket-key pill  (monospace bold, accent color, opaque background)
 *   CENTER — ticket summary  (plain label, truncates)
 *   EAST  — status pill      (bold, status-colored background)
 *
 * Safe to call [updateState] on the EDT at any time; no IO or coroutine dependency.
 */
class HandoverTicketHeader : JPanel(BorderLayout()) {

    // Accent pair mirroring the blue used in existing panels (light / dark).
    private val accentFg = StatusColors.LINK
    private val accentBg = JBColor(0xE8F0FE, 0x004786)

    // ---- Ticket-key pill (WEST) ----
    private val keyPill = JBLabel("").apply {
        font = Font(Font.MONOSPACED, Font.BOLD, JBUI.scale(12))
        foreground = accentFg
        isOpaque = true
        background = accentBg
        border = JBUI.Borders.empty(2, 8)
        horizontalAlignment = SwingConstants.CENTER
    }

    // ---- Summary label (CENTER) ----
    private val summaryLabel = JBLabel("").apply {
        font = font.deriveFont(Font.PLAIN, JBUI.scale(12).toFloat())
        foreground = JBColor.foreground()
        border = JBUI.Borders.empty(0, 8, 0, 8)
    }

    // ---- Status pill (EAST) ----
    private val statusPill = JBLabel("").apply {
        font = font.deriveFont(Font.BOLD, JBUI.scale(11).toFloat())
        foreground = StatusColors.SECONDARY_TEXT
        isOpaque = true
        background = JBColor(0xEEF0F2, 0x1C2740)
        border = JBUI.Borders.empty(2, 8)
        horizontalAlignment = SwingConstants.CENTER
    }

    init {
        isOpaque = false
        border = JBUI.Borders.empty(4, 8)

        // WEST: key pill sits in its own non-opaque wrapper so it doesn't stretch vertically.
        val westWrapper = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            add(keyPill)
        }
        add(westWrapper, BorderLayout.WEST)
        add(summaryLabel, BorderLayout.CENTER)

        val eastWrapper = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
            isOpaque = false
            add(statusPill)
        }
        add(eastWrapper, BorderLayout.EAST)
    }

    /**
     * Refresh all three sub-elements from the canonical [HandoverState].
     * Must be called on the EDT.
     */
    fun updateState(state: HandoverState) {
        if (state.ticketId.isBlank()) {
            keyPill.text = "NO ACTIVE TICKET"
            keyPill.foreground = StatusColors.SECONDARY_TEXT
            keyPill.background = JBColor(0xEEF0F2, 0x1C2740)
            summaryLabel.text = "—"
            statusPill.text = "Unknown"
            statusPill.foreground = StatusColors.SECONDARY_TEXT
        } else {
            keyPill.text = state.ticketId
            keyPill.foreground = accentFg
            keyPill.background = accentBg

            summaryLabel.text = state.ticketSummary.ifBlank { "—" }

            val statusName = state.currentStatusName ?: "Unknown"
            statusPill.text = statusName
            statusPill.foreground = statusForeground(statusName)
        }

        revalidate()
        repaint()
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Maps well-known Jira status names to the appropriate [StatusColors] token.
     * Unrecognised statuses fall back to [StatusColors.SECONDARY_TEXT].
     */
    private fun statusForeground(name: String): JBColor {
        return when {
            name.equals("Done", ignoreCase = true) ||
                name.equals("Closed", ignoreCase = true) ||
                name.equals("Resolved", ignoreCase = true) -> StatusColors.SUCCESS

            name.contains("Progress", ignoreCase = true) ||
                name.contains("Review", ignoreCase = true) ||
                name.contains("Testing", ignoreCase = true) -> StatusColors.WARNING

            name.equals("Blocked", ignoreCase = true) ||
                name.equals("Failed", ignoreCase = true) -> StatusColors.ERROR

            else -> StatusColors.SECONDARY_TEXT
        }
    }
}

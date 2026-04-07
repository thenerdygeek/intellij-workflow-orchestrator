package com.workflow.orchestrator.jira.ui

import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.core.ui.StatusColors
import java.awt.Component
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel

/**
 * Popup shown when a ticket is detected from a branch name change.
 * Asks the user to confirm setting the ticket as active or dismiss.
 */
class TicketDetectionPopup(
    private val ticketKey: String,
    private val summary: String,
    private val sprint: String?,
    private val assignee: String?,
    private val onAccept: () -> Unit,
    private val onDismiss: () -> Unit
) {
    fun show(parentComponent: Component) {
        val content = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(12)
        }

        // Title line
        val titleLabel = JBLabel("Detected ticket from branch").apply {
            foreground = StatusColors.SECONDARY_TEXT
            font = font.deriveFont(JBUI.scale(11).toFloat())
            alignmentX = Component.LEFT_ALIGNMENT
        }
        content.add(titleLabel)
        content.add(javax.swing.Box.createVerticalStrut(JBUI.scale(8)))

        // Ticket key (bold, larger)
        val keyLabel = JBLabel(ticketKey).apply {
            font = font.deriveFont(Font.BOLD, JBUI.scale(16).toFloat())
            alignmentX = Component.LEFT_ALIGNMENT
        }
        content.add(keyLabel)
        content.add(javax.swing.Box.createVerticalStrut(JBUI.scale(4)))

        // Summary
        val summaryLabel = JBLabel(summary).apply {
            font = font.deriveFont(JBUI.scale(12).toFloat())
            alignmentX = Component.LEFT_ALIGNMENT
        }
        content.add(summaryLabel)
        content.add(javax.swing.Box.createVerticalStrut(JBUI.scale(6)))

        // Sprint + Assignee row (dimmed)
        val metaParts = mutableListOf<String>()
        sprint?.let { metaParts.add("Sprint: $it") }
        assignee?.let { metaParts.add("Assignee: $it") }
        if (metaParts.isNotEmpty()) {
            val metaLabel = JBLabel(metaParts.joinToString(" | ")).apply {
                foreground = StatusColors.SECONDARY_TEXT
                font = font.deriveFont(JBUI.scale(11).toFloat())
                alignmentX = Component.LEFT_ALIGNMENT
            }
            content.add(metaLabel)
            content.add(javax.swing.Box.createVerticalStrut(JBUI.scale(10)))
        }

        // Button row
        val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), 0)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
        }

        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(content, null)
            .setTitle("Ticket Detection")
            .setMovable(true)
            .setResizable(false)
            .setRequestFocus(true)
            .setCancelOnClickOutside(true)
            .createPopup()

        val setActiveButton = JButton("Set as Active").apply {
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addActionListener {
                onAccept()
                popup.cancel()
            }
        }

        val dismissButton = JButton("Dismiss").apply {
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addActionListener {
                onDismiss()
                popup.cancel()
            }
        }

        buttonPanel.add(setActiveButton)
        buttonPanel.add(dismissButton)
        content.add(buttonPanel)

        popup.showInCenterOf(parentComponent)
    }
}

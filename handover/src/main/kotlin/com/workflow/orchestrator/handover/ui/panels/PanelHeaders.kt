package com.workflow.orchestrator.handover.ui.panels

import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.core.ui.StatusColors
import java.awt.BorderLayout
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Builds the standard handover panel header: bold uppercase title with a left accent bar
 * and a bottom border separator. Optionally accepts a trailing component (e.g. action button)
 * to be placed on the right side.
 */
internal fun handoverPanelHeader(title: String, eastComponent: JComponent? = null): JPanel {
    val headerLabel = JBLabel(title).apply {
        font = font.deriveFont(Font.BOLD, JBUI.scale(12).toFloat())
        foreground = StatusColors.SECONDARY_TEXT
        border = JBUI.Borders.emptyLeft(8)
    }
    return JPanel(BorderLayout()).apply {
        if (eastComponent != null) {
            add(headerLabel, BorderLayout.WEST)
            add(eastComponent, BorderLayout.EAST)
        } else {
            add(headerLabel, BorderLayout.CENTER)
        }
        border = BorderFactory.createCompoundBorder(
            JBUI.Borders.customLine(StatusColors.BORDER, 0, 0, 1, 0),
            BorderFactory.createCompoundBorder(
                JBUI.Borders.customLine(StatusColors.LINK, 0, 2, 0, 0),
                JBUI.Borders.empty(6, 0, 6, 0)
            )
        )
        isOpaque = false
    }
}

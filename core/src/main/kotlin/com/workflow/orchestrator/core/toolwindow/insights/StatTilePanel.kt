package com.workflow.orchestrator.core.toolwindow.insights

import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.core.ui.StatusColors
import java.awt.BorderLayout
import java.awt.Font
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.border.CompoundBorder
import javax.swing.border.LineBorder
import javax.swing.border.MatteBorder

internal class StatTilePanel(label: String) : JPanel(BorderLayout(0, 4)) {

    private val valueLabel = JBLabel("—", SwingConstants.LEFT).apply {
        font = font.deriveFont(Font.BOLD, 20f)
    }

    init {
        background = StatusColors.CARD_BG
        isOpaque = true
        border = CompoundBorder(
            LineBorder(StatusColors.BORDER, 1, true),
            JBUI.Borders.empty(10, 12),
        )

        val titleLabel = JBLabel(label).apply {
            foreground = StatusColors.SECONDARY_TEXT
            font = font.deriveFont(Font.PLAIN, 11f)
        }

        add(titleLabel, BorderLayout.NORTH)
        add(valueLabel, BorderLayout.CENTER)
    }

    fun setValue(text: String) {
        valueLabel.text = text
    }
}

package com.workflow.orchestrator.agent.delegation.ui

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.workflow.orchestrator.core.ui.StatusColors
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.RenderingHints
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JSeparator
import javax.swing.ListCellRenderer
import javax.swing.SwingConstants

/**
 * Two-line picker row renderer for [PickerEntry]:
 *
 * ```
 *  ●  frontend-app                                       running
 *     /Users/me/work/frontend
 * ```
 *
 * - Status dot (8×8 filled circle) on the left, color from [StatusColors]
 * - Bold repo name on top, dim ellipsized path on second line
 * - Colored status pill on the right
 *
 * Section header entries (`isHeader == true`) render as an uppercased
 * dim label followed by a horizontal separator filling the rest of the row.
 *
 * Per the Plan 5.3 design spec (§4.1, §4.2): JB components only, theme-aware
 * via JBColor / StatusColors, no SVG / icon-library assets.
 */
internal class DelegationPickerCellRenderer : ListCellRenderer<PickerEntry> {

    override fun getListCellRendererComponent(
        list: JList<out PickerEntry>,
        value: PickerEntry,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
    ): Component {
        return if (value.isHeader) {
            buildHeader(value, list)
        } else {
            // Headers are never selected (the picker disables OK on header rows already),
            // so only data rows reflect selection state.
            buildRow(value, list, isSelected)
        }
    }

    // -- Header rows ---------------------------------------------------------

    private fun buildHeader(entry: PickerEntry, list: JList<*>): JComponent {
        val panel = JPanel(GridBagLayout()).apply {
            isOpaque = true
            background = list.background
            border = BorderFactory.createEmptyBorder(12, 12, 8, 12)
        }

        val label = JBLabel(entry.displayName.uppercase()).apply {
            foreground = StatusColors.SECONDARY_TEXT
            // ~80% of list's base font size, bold for the header role.
            val base = list.font ?: font
            font = base.deriveFont(Font.BOLD, base.size2D * 0.8f)
        }

        val separator = JSeparator(SwingConstants.HORIZONTAL).apply {
            foreground = StatusColors.BORDER
            background = list.background
        }

        val labelGbc = GridBagConstraints().apply {
            gridx = 0
            gridy = 0
            anchor = GridBagConstraints.WEST
            insets = Insets(0, 0, 0, 8)
        }
        val sepGbc = GridBagConstraints().apply {
            gridx = 1
            gridy = 0
            weightx = 1.0
            fill = GridBagConstraints.HORIZONTAL
            anchor = GridBagConstraints.CENTER
        }
        panel.add(label, labelGbc)
        panel.add(separator, sepGbc)
        return panel
    }

    // -- Regular data rows ---------------------------------------------------

    private fun buildRow(entry: PickerEntry, list: JList<*>, isSelected: Boolean): JComponent {
        val baseFont = list.font ?: UIFontFallback
        val rowBg = if (isSelected) list.selectionBackground else list.background
        val fg = if (isSelected) list.selectionForeground else list.foreground
        val dimFg = if (isSelected) list.selectionForeground else StatusColors.SECONDARY_TEXT

        val statusColor = colorFor(entry.status)
        val statusText = labelFor(entry.status)

        val panel = JPanel(BorderLayout(8, 0)).apply {
            isOpaque = true
            background = rowBg
            border = BorderFactory.createEmptyBorder(6, 12, 6, 12)
        }

        // West: status dot. Wrap in a small fixed-width panel so the dot doesn't
        // get vertically stretched by BorderLayout — keeping its native 8×8 footprint
        // top-aligned with the bold name line.
        val dotWrap = JPanel(BorderLayout()).apply {
            isOpaque = false
            preferredSize = Dimension(12, 0)
        }
        val dot = StatusDot(statusColor).apply {
            // Center the dot vertically with the bold name line (top text line).
            border = BorderFactory.createEmptyBorder(6, 0, 0, 0)
        }
        // Pin to TOP so the dot sits next to the name (first line), not the path.
        dotWrap.add(dot, BorderLayout.NORTH)
        panel.add(dotWrap, BorderLayout.WEST)

        // Center: two-line stack (name + path)
        val center = JPanel(BorderLayout()).apply {
            isOpaque = false
        }
        val nameLabel = JBLabel(entry.displayName).apply {
            font = baseFont.deriveFont(Font.BOLD)
            foreground = fg
        }
        val pathLabel = JBLabel(ellipsize(entry.path.toString(), 200)).apply {
            font = baseFont.deriveFont(baseFont.size2D * 0.85f)
            foreground = dimFg
            // Ensure overlong paths can be clipped by the layout rather than expanding the row.
            preferredSize = Dimension(0, preferredSize.height)
        }
        center.add(nameLabel, BorderLayout.NORTH)
        center.add(pathLabel, BorderLayout.SOUTH)
        panel.add(center, BorderLayout.CENTER)

        // East: status pill (right-aligned, same line as the name).
        val pillContainer = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
            isOpaque = false
        }
        val pill = JBLabel(statusText).apply {
            font = baseFont.deriveFont(Font.BOLD, baseFont.size2D * 0.85f)
            foreground = if (isSelected) list.selectionForeground else statusColor
            border = BorderFactory.createEmptyBorder(0, 6, 0, 0)
            verticalAlignment = SwingConstants.TOP
        }
        pillContainer.add(pill)
        panel.add(pillContainer, BorderLayout.EAST)

        return panel
    }

    private fun colorFor(status: PickerEntry.Status): JBColor = when (status) {
        PickerEntry.Status.RUNNING -> StatusColors.SUCCESS
        PickerEntry.Status.CLOSED -> StatusColors.SECONDARY_TEXT
        PickerEntry.Status.MISSING -> StatusColors.ERROR
    }

    private fun labelFor(status: PickerEntry.Status): String = when (status) {
        PickerEntry.Status.RUNNING -> "running"
        PickerEntry.Status.CLOSED -> "closed"
        PickerEntry.Status.MISSING -> "missing"
    }

    /**
     * Soft truncation for the path label so a pathological 500-char path doesn't
     * force the row's preferred width past the dialog edge. The layout itself
     * also clips at render time, but trimming the *model* string keeps the
     * tooltip-friendly value compact and prevents BasicLabelUI from running
     * full-text layout on every paint.
     */
    private fun ellipsize(s: String, max: Int): String {
        if (s.length <= max) return s
        // Keep the head + tail of the path — what the user actually identifies a project by.
        val keepHead = (max * 0.4).toInt()
        val keepTail = max - keepHead - 1
        return s.take(keepHead) + "…" + s.takeLast(keepTail)
    }

    companion object {
        private val UIFontFallback: Font = Font(Font.DIALOG, Font.PLAIN, 12)
    }

    /**
     * Small filled-circle component (8×8 px) painted in the given JBColor.
     * Anti-aliased so the disk reads clean on HiDPI displays.
     */
    private class StatusDot(private val dotColor: Color) : JComponent() {
        init {
            preferredSize = Dimension(SIZE, SIZE)
            minimumSize = preferredSize
            isOpaque = false
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = dotColor
                // Center the circle inside the component bounds.
                val cx = (width - SIZE) / 2
                val cy = (height - SIZE) / 2
                g2.fillOval(cx, cy, SIZE, SIZE)
            } finally {
                g2.dispose()
            }
        }

        companion object {
            private const val SIZE = 8
        }
    }
}

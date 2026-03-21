package com.workflow.orchestrator.jira.ui

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.workflow.orchestrator.core.ui.StatusColors
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Reusable collapsible section with a clickable header (▶/▼ + title + count).
 * Content shows/hides when header is clicked.
 */
class CollapsibleSection(
    title: String,
    private val content: JComponent,
    initiallyExpanded: Boolean = true,
    count: Int? = null
) : JPanel(BorderLayout()) {

    private var expanded = initiallyExpanded
    private val arrowLabel = JBLabel(if (expanded) "▼" else "▶").apply {
        font = font.deriveFont(JBUI.scale(10).toFloat())
        foreground = StatusColors.SECONDARY_TEXT
    }
    private val titleLabel = JBLabel(title).apply {
        font = font.deriveFont(Font.BOLD, JBUI.scale(10).toFloat())
        foreground = StatusColors.SECONDARY_TEXT
    }
    private val countLabel = JBLabel(count?.let { "($it)" } ?: "").apply {
        font = font.deriveFont(JBUI.scale(10).toFloat())
        foreground = StatusColors.SECONDARY_TEXT
    }

    init {
        isOpaque = false

        val header = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(2))).apply {
            isOpaque = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            border = JBUI.Borders.empty(2, 4)
            add(arrowLabel)
            add(titleLabel)
            add(countLabel)
        }

        header.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                toggle()
            }
        })

        add(header, BorderLayout.NORTH)
        add(content, BorderLayout.CENTER)

        content.isVisible = expanded
    }

    fun toggle() {
        expanded = !expanded
        content.isVisible = expanded
        arrowLabel.text = if (expanded) "▼" else "▶"
        revalidate()
        repaint()
    }

    fun setExpanded(expand: Boolean) {
        if (expanded != expand) toggle()
    }

    fun updateCount(newCount: Int) {
        countLabel.text = "($newCount)"
    }

    fun updateTitle(newTitle: String) {
        titleLabel.text = newTitle
    }
}

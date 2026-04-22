package com.workflow.orchestrator.pullrequest.ui

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.core.model.PrComment
import com.workflow.orchestrator.core.model.PrCommentState
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.Font
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.BorderFactory
import javax.swing.JList
import javax.swing.ListCellRenderer

/**
 * JBList cell renderer for [PrComment].
 * Shows author, timestamp, state pill, optional file anchor, and truncated body text.
 */
class CommentRowRenderer : ListCellRenderer<PrComment> {
    private val df = SimpleDateFormat("yyyy-MM-dd HH:mm")

    override fun getListCellRendererComponent(
        list: JList<out PrComment>,
        value: PrComment,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
    ): Component {
        val root = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = JBUI.Borders.compound(
                BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor.border()),
                JBUI.Borders.empty(8, 12),
            )
            background = if (isSelected) list.selectionBackground else list.background
            isOpaque = true
        }

        // Header row: author · time · state pill · optional anchor
        val header = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            isOpaque = false
            add(JBLabel(value.author.displayName).apply {
                font = font.deriveFont(Font.BOLD)
            })
            add(JBLabel(df.format(Date(value.createdDate))).apply {
                foreground = JBColor.GRAY
            })
            val stateText = if (value.state == PrCommentState.RESOLVED) "RESOLVED" else "OPEN"
            val stateColor = if (value.state == PrCommentState.RESOLVED) JBColor.GREEN.darker() else JBColor.BLUE
            add(JBLabel(stateText).apply {
                foreground = stateColor
                font = font.deriveFont(Font.BOLD, 10f)
            })
            value.anchor?.let { anchor ->
                val anchorText = buildString {
                    append(anchor.path)
                    anchor.line?.let { append(":$it") }
                }
                add(JBLabel(anchorText).apply {
                    foreground = JBColor.GRAY
                    font = font.deriveFont(Font.ITALIC, 10f)
                })
            }
        }

        val bodyText = value.text.take(500).htmlEscape()
        val body = JBLabel("<html>$bodyText</html>").apply {
            verticalAlignment = JBLabel.TOP
        }

        root.add(header, BorderLayout.NORTH)
        root.add(body, BorderLayout.CENTER)
        return root
    }

    private fun String.htmlEscape(): String =
        replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\n", "<br>")
}

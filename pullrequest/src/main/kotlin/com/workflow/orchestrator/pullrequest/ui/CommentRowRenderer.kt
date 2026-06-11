package com.workflow.orchestrator.pullrequest.ui

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.core.model.PrComment
import com.workflow.orchestrator.core.model.PrCommentState
import com.workflow.orchestrator.core.util.HtmlEscape
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
 *
 * Rubber-stamp pattern (P1-20): the component tree is built ONCE and reconfigured per cell —
 * the comments list repaints on a 30s poll, so per-paint allocation of panels/labels and
 * per-paint `deriveFont` are pure churn. Fonts come from the cached [RendererFonts] keyed by
 * the list's live font (LAF-safe), and the body HTML is only rebuilt when the text changes.
 */
class CommentRowRenderer : ListCellRenderer<PrComment> {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm")

    private val authorLabel = JBLabel()

    private val timeLabel = JBLabel().apply { foreground = JBColor.GRAY }

    private val stateLabel = JBLabel()

    private val anchorLabel = JBLabel().apply { foreground = JBColor.GRAY }

    private val bodyLabel = JBLabel().apply { verticalAlignment = JBLabel.TOP }

    /** Last comment text rendered into [bodyLabel] — skips the HTML rebuild on repeated paints. */
    private var lastBodyText: String? = null

    private val header = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, HEADER_HGAP, 0)).apply {
        isOpaque = false
        add(authorLabel)
        add(timeLabel)
        add(stateLabel)
        add(anchorLabel)
    }

    private val root = JBPanel<JBPanel<*>>(BorderLayout()).apply {
        border = JBUI.Borders.compound(
            BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor.border()),
            JBUI.Borders.empty(ROW_VPAD, ROW_HPAD),
        )
        isOpaque = true
        add(header, BorderLayout.NORTH)
        add(bodyLabel, BorderLayout.CENTER)
    }

    override fun getListCellRendererComponent(
        list: JList<out PrComment>,
        value: PrComment,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
    ): Component {
        root.background = if (isSelected) list.selectionBackground else list.background

        val baseFont = list.font
        authorLabel.text = value.author.displayName
        authorLabel.font = RendererFonts.derive(baseFont, Font.BOLD)

        timeLabel.text = dateFormat.format(Date(value.createdDate))
        timeLabel.font = baseFont

        val resolved = value.state == PrCommentState.RESOLVED
        stateLabel.text = if (resolved) "RESOLVED" else "OPEN"
        stateLabel.foreground = if (resolved) JBColor.GREEN.darker() else JBColor.BLUE
        stateLabel.font = RendererFonts.derive(baseFont, Font.BOLD, SMALL_FONT_SIZE)

        val anchor = value.anchor
        if (anchor != null) {
            anchorLabel.text = anchorText(anchor.path, anchor.line)
            anchorLabel.font = RendererFonts.derive(baseFont, Font.ITALIC, SMALL_FONT_SIZE)
            anchorLabel.isVisible = true
        } else {
            anchorLabel.isVisible = false
        }

        if (value.text != lastBodyText) {
            lastBodyText = value.text
            bodyLabel.text = bodyHtml(value.text)
        }
        bodyLabel.font = baseFont

        return root
    }

    companion object {
        private const val HEADER_HGAP = 8
        private const val ROW_VPAD = 8
        private const val ROW_HPAD = 12
        private const val MAX_BODY_CHARS = 500
        private const val SMALL_FONT_SIZE = 10f

        /** Pure anchor text: "path" or "path:line". */
        @JvmStatic
        internal fun anchorText(path: String, line: Int?): String = buildString {
            append(path)
            line?.let { append(":$it") }
        }

        /** Pure body HTML: escape, truncate to [MAX_BODY_CHARS], newline → `<br>`. */
        @JvmStatic
        internal fun bodyHtml(text: String): String =
            "<html>${HtmlEscape.escapeHtml(text.take(MAX_BODY_CHARS)).replace("\n", "<br>")}</html>"
    }
}

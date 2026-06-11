package com.workflow.orchestrator.pullrequest.ui

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.core.prreview.FindingSeverity
import com.workflow.orchestrator.core.prreview.PrReviewFinding
import com.workflow.orchestrator.core.util.HtmlEscape
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.JList
import javax.swing.ListCellRenderer

/**
 * JBList cell renderer for [PrReviewFinding].
 *
 * Rubber-stamp pattern (P1-20): the component tree (root + header + ~4 labels) is built ONCE
 * and reconfigured per cell, instead of being re-allocated on every paint. Fonts are pulled
 * from the cached [RendererFonts] (keyed by the list's live font, so LAF changes propagate),
 * and the body's HTML is only rebuilt when the message actually changes.
 */
class FindingRowRenderer : ListCellRenderer<PrReviewFinding> {

    private val severityLabel = JBLabel()

    private val anchorLabel = JBLabel().apply { foreground = JBColor.GRAY }

    private val pushedLabel = JBLabel("✓ pushed").apply { foreground = JBColor.GREEN }

    private val discardedLabel = JBLabel("✗ discarded").apply { foreground = JBColor.GRAY }

    private val bodyLabel = JBLabel().apply { verticalAlignment = JBLabel.TOP }

    /** Last message rendered into [bodyLabel] — skips the HTML rebuild on repeated paints. */
    private var lastBodyMessage: String? = null

    private val header = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, HEADER_HGAP, 0)).apply {
        isOpaque = false
        add(severityLabel)
        add(anchorLabel)
        add(pushedLabel)
        add(discardedLabel)
    }

    private val root = JBPanel<JBPanel<*>>(BorderLayout()).apply {
        border = JBUI.Borders.compound(
            BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor.border()),
            JBUI.Borders.empty(ROW_VPAD, ROW_HPAD),
        )
        add(header, BorderLayout.NORTH)
        add(bodyLabel, BorderLayout.CENTER)
    }

    override fun getListCellRendererComponent(
        list: JList<out PrReviewFinding>,
        value: PrReviewFinding,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
    ): Component {
        root.background = if (isSelected) JBColor.background().darker() else JBColor.background()

        val baseFont = list.font
        severityLabel.text = value.severity.name
        severityLabel.foreground =
            if (value.severity == FindingSeverity.BLOCKER) JBColor.RED else JBColor.foreground()
        severityLabel.font = RendererFonts.derive(baseFont, Font.BOLD)

        anchorLabel.text = anchorText(value.file, value.lineStart)
        anchorLabel.font = baseFont

        pushedLabel.isVisible = value.pushed
        pushedLabel.font = baseFont
        discardedLabel.isVisible = value.discarded
        discardedLabel.font = baseFont

        if (value.message != lastBodyMessage) {
            lastBodyMessage = value.message
            bodyLabel.text = bodyHtml(value.message)
        }
        bodyLabel.font = baseFont

        return root
    }

    companion object {
        private const val HEADER_HGAP = 8
        private const val ROW_VPAD = 8
        private const val ROW_HPAD = 12
        private const val MAX_BODY_CHARS = 600

        /** Pure header-anchor text: "file:line", "file", or "general" when there is no file. */
        @JvmStatic
        internal fun anchorText(file: String?, lineStart: Int?): String =
            if (file != null) "$file${lineStart?.let { ":$it" } ?: ""}" else "general"

        /** Pure body HTML: escape, truncate to [MAX_BODY_CHARS], newline → `<br>`. */
        @JvmStatic
        internal fun bodyHtml(message: String): String =
            "<html>${HtmlEscape.escapeHtml(message.take(MAX_BODY_CHARS)).replace("\n", "<br>")}</html>"
    }
}

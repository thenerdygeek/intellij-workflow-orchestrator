package com.workflow.orchestrator.handover.ui.editor

import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * Email preview pane backing the [PreviewPane] interface for the Share / Email tab.
 *
 * Renders Outlook-style HTML in a read-only [JEditorPane]. No live-render badge,
 * no service dependency — just a simple HTML host.
 *
 * The format pill ("HTML for Outlook") lives on [TemplateEditorCard]'s title row,
 * not here.
 */
class EmailPreviewPane : JPanel(BorderLayout()), PreviewPane {

    /** Raw HTML last set on the editor — preserved so [getText] bypasses the HTML parser's
     *  round-trip reformatting, keeping tests and the editor in sync with the source markup. */
    @Volatile
    private var rawHtml: String = ""

    /**
     * Custom [JEditorPane] that overrides [getText] to return the raw HTML string set via
     * [setText], bypassing the HTML parser's round-trip reformatting so tests can do
     * simple substring checks on the markup we actually painted.
     */
    private val editor: JEditorPane = object : JEditorPane() {
        override fun getText(): String = rawHtml
    }.also {
        it.contentType = "text/html"
        it.isEditable = false
    }

    init {
        add(JBScrollPane(editor), BorderLayout.CENTER)
    }

    // ── PreviewPane ──────────────────────────────────────────────────────────

    override fun setRenderedMarkup(html: String) {
        setEditorText(html)
    }

    override fun asComponent(): JComponent = this

    // ── Private helpers ──────────────────────────────────────────────────────

    /** Stores [html] in [rawHtml] and calls [JEditorPane.setText] on the EDT (or directly if already on EDT). */
    private fun setEditorText(html: String) {
        rawHtml = html
        if (SwingUtilities.isEventDispatchThread()) {
            editor.setText(html)
        } else {
            SwingUtilities.invokeLater { editor.setText(html) }
        }
    }
}

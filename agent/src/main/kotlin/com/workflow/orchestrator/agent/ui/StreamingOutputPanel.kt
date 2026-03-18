package com.workflow.orchestrator.agent.ui

import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.text.DefaultCaret

/**
 * Panel that displays streaming LLM output token-by-token.
 * Auto-scrolls as new content arrives. Supports appending text
 * and clearing for new sessions.
 */
class StreamingOutputPanel : JPanel(BorderLayout()) {

    private val textArea = JBTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        font = JBUI.Fonts.create("Monospaced", 12)
        border = JBUI.Borders.empty(8)
        // Auto-scroll to bottom as text is appended
        (caret as? DefaultCaret)?.updatePolicy = DefaultCaret.ALWAYS_UPDATE
    }

    init {
        border = JBUI.Borders.empty()
        add(JBScrollPane(textArea), BorderLayout.CENTER)
    }

    /** Append streaming text (called from any thread — ensures EDT safety). */
    fun appendText(text: String) {
        if (SwingUtilities.isEventDispatchThread()) {
            textArea.append(text)
        } else {
            SwingUtilities.invokeLater { textArea.append(text) }
        }
    }

    /** Set the full text content (replaces everything). */
    fun setText(text: String) {
        if (SwingUtilities.isEventDispatchThread()) {
            textArea.text = text
        } else {
            SwingUtilities.invokeLater { textArea.text = text }
        }
    }

    /** Clear all content for a new session. */
    fun clear() {
        if (SwingUtilities.isEventDispatchThread()) {
            textArea.text = ""
        } else {
            SwingUtilities.invokeLater { textArea.text = "" }
        }
    }

    /** Append a separator line between sections. */
    fun appendSeparator(label: String = "") {
        val separator = if (label.isNotBlank()) "\n── $label ──\n" else "\n────────────\n"
        appendText(separator)
    }

    /** Append a tool call summary (formatted differently from reasoning text). */
    fun appendToolCall(toolName: String, summary: String) {
        appendText("\n> Tool: $toolName → $summary\n")
    }
}

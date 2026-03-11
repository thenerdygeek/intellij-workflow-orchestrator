package com.workflow.orchestrator.bamboo.ui

import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.bamboo.model.BuildError
import com.workflow.orchestrator.bamboo.model.ErrorSeverity
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Font
import javax.swing.*
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants

class StageDetailPanel : JPanel(BorderLayout()) {

    private val logPane = JTextPane().apply {
        isEditable = false
        font = Font(Font.MONOSPACED, Font.PLAIN, JBUI.scaleFontSize(12f).toInt())
        border = JBUI.Borders.empty(8)
    }

    private val errorListModel = DefaultListModel<BuildError>()
    private val errorList = JList(errorListModel).apply {
        cellRenderer = ErrorListCellRenderer()
    }

    private val tabbedPane = JBTabbedPane().apply {
        addTab("Log", JBScrollPane(logPane))
        addTab("Errors", JBScrollPane(errorList))
    }

    init {
        border = JBUI.Borders.empty()
        add(tabbedPane, BorderLayout.CENTER)
    }

    fun showLog(log: String, errors: List<BuildError>) {
        // Log tab with error highlighting
        val doc = logPane.styledDocument
        doc.remove(0, doc.length)
        for (line in log.lines()) {
            val attrs = SimpleAttributeSet()
            when {
                line.contains("[ERROR]") -> {
                    StyleConstants.setForeground(attrs, Color(0xCC, 0x33, 0x33))
                    StyleConstants.setBold(attrs, true)
                }
                line.contains("[WARNING]") -> {
                    StyleConstants.setForeground(attrs, Color(0xCC, 0x99, 0x33))
                }
            }
            doc.insertString(doc.length, line + "\n", attrs)
        }
        logPane.caretPosition = 0

        // Errors tab
        errorListModel.clear()
        errors.forEach { errorListModel.addElement(it) }

        // Switch to Errors tab if there are errors
        if (errors.any { it.severity == ErrorSeverity.ERROR }) {
            tabbedPane.selectedIndex = 1
        }
    }

    fun showEmpty() {
        logPane.text = ""
        errorListModel.clear()
    }

    private class ErrorListCellRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>, value: Any?, index: Int, selected: Boolean, hasFocus: Boolean
        ): Component {
            super.getListCellRendererComponent(list, value, index, selected, hasFocus)
            val error = value as? BuildError ?: return this
            border = JBUI.Borders.empty(4, 8)

            val prefix = when (error.severity) {
                ErrorSeverity.ERROR -> "ERROR"
                ErrorSeverity.WARNING -> "WARN"
            }
            val location = if (error.filePath != null) {
                val line = error.lineNumber?.let { ":$it" } ?: ""
                " ${error.filePath}$line"
            } else ""

            text = "[$prefix]$location — ${error.message}"

            if (!selected) {
                foreground = when (error.severity) {
                    ErrorSeverity.ERROR -> Color(0xCC, 0x33, 0x33)
                    ErrorSeverity.WARNING -> Color(0xCC, 0x99, 0x33)
                }
            }
            return this
        }
    }
}

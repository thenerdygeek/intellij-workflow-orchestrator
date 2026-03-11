package com.workflow.orchestrator.bamboo.ui

import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.bamboo.model.BuildError
import com.workflow.orchestrator.bamboo.model.ErrorSeverity
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Font
import javax.swing.*
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants

class StageDetailPanel : JPanel(BorderLayout()) {

    companion object {
        private val ERROR_COLOR = JBColor(Color(0xCC, 0x33, 0x33), Color(0xFF, 0x66, 0x66))
        private val WARNING_COLOR = JBColor(Color(0xCC, 0x99, 0x33), Color(0xFF, 0xCC, 0x66))
    }

    private val logPane = JTextPane().apply {
        isEditable = false
        font = Font(Font.MONOSPACED, Font.PLAIN, JBUI.scaleFontSize(12f).toInt())
        border = JBUI.Borders.empty(8)
    }

    private val errorListModel = DefaultListModel<BuildError>()
    private val errorList = JBList(errorListModel).apply {
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
                    StyleConstants.setForeground(attrs, ERROR_COLOR)
                    StyleConstants.setBold(attrs, true)
                }
                line.contains("[WARNING]") -> {
                    StyleConstants.setForeground(attrs, WARNING_COLOR)
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

    private class ErrorListCellRenderer : ColoredListCellRenderer<BuildError>() {
        override fun customizeCellRenderer(
            list: JList<out BuildError>,
            value: BuildError?,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean
        ) {
            value ?: return
            border = JBUI.Borders.empty(4, 8)

            val prefix = when (value.severity) {
                ErrorSeverity.ERROR -> "ERROR"
                ErrorSeverity.WARNING -> "WARN"
            }
            val location = if (value.filePath != null) {
                val line = value.lineNumber?.let { ":$it" } ?: ""
                " ${value.filePath}$line"
            } else ""

            val attrs = when (value.severity) {
                ErrorSeverity.ERROR -> SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, ERROR_COLOR)
                ErrorSeverity.WARNING -> SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, WARNING_COLOR)
            }
            append("[$prefix]$location — ${value.message}", attrs)
        }
    }
}

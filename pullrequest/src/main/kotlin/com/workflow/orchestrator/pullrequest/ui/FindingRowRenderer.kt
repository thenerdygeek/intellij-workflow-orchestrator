package com.workflow.orchestrator.pullrequest.ui

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.core.prreview.FindingSeverity
import com.workflow.orchestrator.core.prreview.PrReviewFinding
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.JList
import javax.swing.ListCellRenderer

class FindingRowRenderer : ListCellRenderer<PrReviewFinding> {
    override fun getListCellRendererComponent(
        list: JList<out PrReviewFinding>,
        value: PrReviewFinding,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
    ): Component {
        val root = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = JBUI.Borders.compound(
                BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor.border()),
                JBUI.Borders.empty(8, 12),
            )
            background = if (isSelected) JBColor.background().darker() else JBColor.background()
        }
        val header = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            isOpaque = false
            val severityColor = if (value.severity == FindingSeverity.BLOCKER) JBColor.RED else JBColor.foreground()
            add(JBLabel(value.severity.name).apply { foreground = severityColor; font = font.deriveFont(Font.BOLD) })
            val anchor = if (value.file != null) "${value.file}${value.lineStart?.let { ":$it" } ?: ""}" else "general"
            add(JBLabel(anchor).apply { foreground = JBColor.GRAY })
            if (value.pushed) add(JBLabel("✓ pushed").apply { foreground = JBColor.GREEN })
            if (value.discarded) add(JBLabel("✗ discarded").apply { foreground = JBColor.GRAY })
        }
        val body = JBLabel("<html>${value.message.take(600).htmlEscape()}</html>").apply {
            verticalAlignment = JBLabel.TOP
        }
        root.add(header, BorderLayout.NORTH)
        root.add(body, BorderLayout.CENTER)
        return root
    }

    private fun String.htmlEscape(): String =
        replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>")
}

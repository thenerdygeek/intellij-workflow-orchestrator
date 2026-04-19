package com.workflow.orchestrator.core.toolwindow.insights

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.core.model.insights.SessionRecord
import com.workflow.orchestrator.core.ui.TimeFormatter
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.table.DefaultTableModel

internal class SessionsPanel : JPanel(BorderLayout()) {

    private val COLUMNS = arrayOf("Time", "Task", "Model", "Tokens In", "Tokens Out", "Est. Cost")
    private val MAX_SESSIONS = 200

    private val tableModel = object : DefaultTableModel(COLUMNS, 0) {
        override fun isCellEditable(row: Int, col: Int) = false
    }

    private val table = com.intellij.ui.table.JBTable(tableModel).apply {
        setShowGrid(false)
        tableHeader.reorderingAllowed = false
        autoResizeMode = javax.swing.JTable.AUTO_RESIZE_LAST_COLUMN
        columnModel.getColumn(0).preferredWidth = 90
        columnModel.getColumn(1).preferredWidth = 280
        columnModel.getColumn(2).preferredWidth = 120
        columnModel.getColumn(3).preferredWidth = 90
        columnModel.getColumn(4).preferredWidth = 90
        columnModel.getColumn(5).preferredWidth = 80
    }

    private val footerLabel = JBLabel("").apply {
        foreground = JBUI.CurrentTheme.Label.disabledForeground()
        font = font.deriveFont(Font.PLAIN, 11f)
        border = JBUI.Borders.empty(4, 8)
    }

    private val emptyLabel = JBLabel(
        "<html><center>No sessions yet.<br>Start a conversation with the agent.</center></html>"
    ).apply {
        horizontalAlignment = SwingConstants.CENTER
        foreground = JBUI.CurrentTheme.Label.disabledForeground()
    }

    init {
        add(JBScrollPane(table), BorderLayout.CENTER)
        val footer = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
        footer.add(footerLabel)
        add(footer, BorderLayout.SOUTH)
    }

    fun update(sessions: List<SessionRecord>) {
        if (sessions.isEmpty()) {
            showEmpty()
            return
        }

        showContent()
        tableModel.rowCount = 0
        val shown = sessions.sortedByDescending { it.ts }.take(MAX_SESSIONS)
        shown.forEach { rec ->
            val time = TimeFormatter.relative(rec.ts)
            val task = if (rec.task.length > 60) rec.task.take(57) + "…" else rec.task
            val model = rec.modelId?.substringAfterLast("/") ?: "—"
            val tokIn = InsightsFormatters.formatTokenCount(rec.tokensIn)
            val tokOut = InsightsFormatters.formatTokenCount(rec.tokensOut)
            val cost = InsightsFormatters.formatCost(rec.totalCost, rec.totalCost > 0.0)
            tableModel.addRow(arrayOf(time, task, model, tokIn, tokOut, cost))
        }

        footerLabel.text = if (sessions.size > MAX_SESSIONS)
            "Showing last $MAX_SESSIONS of ${sessions.size} sessions"
        else
            "${sessions.size} session${if (sessions.size == 1) "" else "s"}"
    }

    private fun showEmpty() {
        removeAll()
        add(emptyLabel, BorderLayout.CENTER)
        revalidate(); repaint()
    }

    private fun showContent() {
        removeAll()
        add(JBScrollPane(table), BorderLayout.CENTER)
        val footer = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
        footer.add(footerLabel)
        add(footer, BorderLayout.SOUTH)
        revalidate(); repaint()
    }
}

package com.workflow.orchestrator.core.toolwindow.insights

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.core.services.InsightsStats
import com.workflow.orchestrator.core.ui.StatusColors
import java.awt.*
import javax.swing.*
import javax.swing.table.DefaultTableModel

internal class TodayPanel : JPanel(BorderLayout()) {

    private val tileRow = JPanel(GridLayout(1, 4, 8, 0)).apply {
        isOpaque = false
        border = JBUI.Borders.empty(8, 8, 4, 8)
    }

    private val sessionsTile = StatTilePanel("Sessions")
    private val tokensTile = StatTilePanel("Tokens")
    private val costTile = StatTilePanel("Est. Cost")
    private val toolCallsTile = StatTilePanel("Tool Calls")

    private val successBar = JProgressBar(0, 100).apply {
        isStringPainted = true
        string = "No data"
    }

    private val topToolsModel = DefaultTableModel(arrayOf("Tool", "Calls"), 0)
    private val topToolsTable = com.intellij.ui.table.JBTable(topToolsModel).apply {
        isEnabled = false
        setShowGrid(false)
        tableHeader.reorderingAllowed = false
        setColumnSelectionAllowed(false)
        columnModel.getColumn(0).preferredWidth = 200
        columnModel.getColumn(1).preferredWidth = 80
    }

    private val errorsModel = DefaultListModel<String>()
    private val errorsList = JList(errorsModel).apply { isEnabled = false }

    private val emptyLabel = JBLabel(
        "<html><center>No sessions today.<br>Start a conversation with the agent.</center></html>"
    ).apply {
        horizontalAlignment = SwingConstants.CENTER
        foreground = JBUI.CurrentTheme.Label.disabledForeground()
    }

    private val contentPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        border = JBUI.Borders.empty(0, 8, 8, 8)
    }

    init {
        tileRow.add(sessionsTile)
        tileRow.add(tokensTile)
        tileRow.add(costTile)
        tileRow.add(toolCallsTile)

        contentPanel.add(buildSectionHeader("Success Rate"))
        contentPanel.add(Box.createVerticalStrut(4))
        contentPanel.add(buildSuccessBarRow())
        contentPanel.add(Box.createVerticalStrut(12))
        contentPanel.add(buildSectionHeader("Top Tools"))
        contentPanel.add(Box.createVerticalStrut(4))
        contentPanel.add(JBScrollPane(topToolsTable).apply { preferredSize = Dimension(Int.MAX_VALUE, 120) })
        contentPanel.add(Box.createVerticalStrut(12))
        contentPanel.add(buildSectionHeader("Recent Errors"))
        contentPanel.add(Box.createVerticalStrut(4))
        contentPanel.add(JBScrollPane(errorsList).apply { preferredSize = Dimension(Int.MAX_VALUE, 80) })

        add(tileRow, BorderLayout.NORTH)
        add(JBScrollPane(contentPanel).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
    }

    fun update(stats: InsightsStats) {
        val hasData = stats.sessionCount > 0
        val hasRealCost = stats.totalCostUsd > 0.0

        sessionsTile.setValue(stats.sessionCount.toString())
        tokensTile.setValue(InsightsFormatters.formatTokenPair(stats.totalTokensIn, stats.totalTokensOut))
        costTile.setValue(InsightsFormatters.formatCost(stats.totalCostUsd, hasRealCost))
        toolCallsTile.setValue(if (hasData) stats.totalToolCalls.toString() else "—")

        if (hasData && stats.totalToolCalls > 0) {
            val pct = ((stats.totalToolCalls - stats.failedToolCalls).toDouble() / stats.totalToolCalls * 100).toInt()
            successBar.value = pct
            successBar.string = "$pct% success"
        } else {
            successBar.value = 0
            successBar.string = if (hasData) "N/A" else "No data"
        }

        topToolsModel.rowCount = 0
        if (stats.topTools.isEmpty()) {
            topToolsModel.addRow(arrayOf("—", "—"))
        } else {
            stats.topTools.forEach { (name, count) -> topToolsModel.addRow(arrayOf<Any>(name, count)) }
        }

        errorsModel.clear()
        if (stats.recentErrors.isEmpty()) {
            errorsModel.addElement("No errors recorded.")
        } else {
            stats.recentErrors.forEach { errorsModel.addElement(it) }
        }

        if (!hasData) {
            showEmpty()
        } else {
            showContent()
        }
    }

    private fun showEmpty() {
        removeAll()
        add(emptyLabel, BorderLayout.CENTER)
        revalidate(); repaint()
    }

    private fun showContent() {
        removeAll()
        add(tileRow, BorderLayout.NORTH)
        add(JBScrollPane(contentPanel).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
        revalidate(); repaint()
    }

    private fun buildSectionHeader(title: String): JBLabel = JBLabel(title.uppercase()).apply {
        foreground = StatusColors.SECONDARY_TEXT
        font = font.deriveFont(Font.BOLD, 10f)
        border = JBUI.Borders.emptyBottom(2)
        alignmentX = Component.LEFT_ALIGNMENT
    }

    private fun buildSuccessBarRow(): JPanel = JPanel(BorderLayout()).apply {
        isOpaque = false
        maximumSize = Dimension(Int.MAX_VALUE, 24)
        alignmentX = Component.LEFT_ALIGNMENT
        add(successBar, BorderLayout.CENTER)
    }
}

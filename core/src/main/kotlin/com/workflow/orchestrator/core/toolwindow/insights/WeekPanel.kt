package com.workflow.orchestrator.core.toolwindow.insights

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.core.model.insights.SessionRecord
import com.workflow.orchestrator.core.services.InsightsStats
import com.workflow.orchestrator.core.ui.StatusColors
import java.awt.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.swing.*

internal class WeekPanel : JPanel(BorderLayout()) {

    private val tileRow = JPanel(GridLayout(1, 4, 8, 0)).apply {
        isOpaque = false
        border = JBUI.Borders.empty(8, 8, 4, 8)
    }

    private val sessionsTile = StatTilePanel("Sessions")
    private val tokensTile = StatTilePanel("Tokens")
    private val costTile = StatTilePanel("Est. Cost")
    private val toolCallsTile = StatTilePanel("Tool Calls")

    private val sparklineLabel = JBLabel("").apply {
        font = font.deriveFont(14f)
    }

    private val modelRows = DefaultListModel<String>()
    private val modelList = JList(modelRows).apply { isEnabled = false }

    private val contentPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        border = JBUI.Borders.empty(0, 8, 8, 8)
    }

    private val emptyLabel = JBLabel(
        "<html><center>No sessions this week.</center></html>"
    ).apply {
        horizontalAlignment = SwingConstants.CENTER
        foreground = JBUI.CurrentTheme.Label.disabledForeground()
    }

    init {
        tileRow.add(sessionsTile)
        tileRow.add(tokensTile)
        tileRow.add(costTile)
        tileRow.add(toolCallsTile)

        contentPanel.add(buildSectionHeader("Sessions per Day (7 days)"))
        contentPanel.add(Box.createVerticalStrut(4))
        contentPanel.add(buildSparklineRow())
        contentPanel.add(Box.createVerticalStrut(12))
        contentPanel.add(buildSectionHeader("Model Usage"))
        contentPanel.add(Box.createVerticalStrut(4))
        contentPanel.add(JBScrollPane(modelList).apply { preferredSize = Dimension(Int.MAX_VALUE, 100) })

        add(tileRow, BorderLayout.NORTH)
        add(JBScrollPane(contentPanel).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
    }

    fun update(stats: InsightsStats, all: List<SessionRecord>) {
        val sevenDaysAgo = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
        val weekSessions = all.filter { it.ts >= sevenDaysAgo }
        val hasData = weekSessions.isNotEmpty()
        val hasRealCost = stats.totalCostUsd > 0.0

        sessionsTile.setValue(stats.sessionCount.toString())
        tokensTile.setValue(InsightsFormatters.formatTokenPair(stats.totalTokensIn, stats.totalTokensOut))
        costTile.setValue(InsightsFormatters.formatCost(stats.totalCostUsd, hasRealCost))
        toolCallsTile.setValue(if (hasData) stats.totalToolCalls.toString() else "—")

        sparklineLabel.text = buildSparkline(weekSessions) + "  (7 days)"

        modelRows.clear()
        val modelCounts = weekSessions
            .groupBy { it.modelId ?: "unknown" }
            .mapValues { it.value.size }
            .entries
            .sortedByDescending { it.value }
        if (modelCounts.isEmpty()) {
            modelRows.addElement("—")
        } else {
            modelCounts.forEach { (model, count) -> modelRows.addElement("$model  ×$count") }
        }

        if (!hasData) {
            showEmpty()
        } else {
            showContent()
        }
    }

    private fun buildSparkline(sessions: List<SessionRecord>): String {
        val bars = "▁▂▃▄▅▆▇█"
        val today = LocalDate.now(ZoneId.systemDefault())
        val counts = (0..6).map { daysAgo ->
            val day = today.minusDays(daysAgo.toLong())
            sessions.count { item ->
                val itemDay = Instant.ofEpochMilli(item.ts).atZone(ZoneId.systemDefault()).toLocalDate()
                itemDay == day
            }
        }.reversed()

        val maxCount = counts.maxOrNull() ?: 0
        if (maxCount == 0) return "▁▁▁▁▁▁▁"
        return counts.map { count ->
            val idx = ((count.toDouble() / maxCount) * (bars.length - 1)).toInt().coerceIn(0, bars.length - 1)
            bars[idx]
        }.joinToString("")
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

    private fun buildSparklineRow(): JPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
        isOpaque = false
        maximumSize = Dimension(Int.MAX_VALUE, 28)
        alignmentX = Component.LEFT_ALIGNMENT
        add(sparklineLabel)
    }
}

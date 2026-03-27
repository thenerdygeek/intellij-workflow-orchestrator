package com.workflow.orchestrator.jira.ui

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.core.ui.StatusColors
import com.workflow.orchestrator.jira.api.dto.JiraIssue
import com.workflow.orchestrator.jira.api.dto.JiraSprint
import java.awt.*
import java.awt.geom.RoundRectangle2D
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import javax.swing.JPanel

/**
 * Composite bar showing sprint time remaining with urgency coloring
 * and a proportional ticket breakdown (done/in-progress/todo).
 * Uses a side-by-side layout with uppercase section labels.
 */
class SprintTimeBar : JPanel() {

    // Time column labels
    private val timeHeaderLabel = JBLabel("TIME ELAPSED").apply {
        font = font.deriveFont(Font.BOLD, JBUI.scale(10).toFloat())
        foreground = StatusColors.SECONDARY_TEXT
    }
    private val timeValueLabel = JBLabel("").apply {
        font = font.deriveFont(JBUI.scale(10).toFloat())
        horizontalAlignment = JBLabel.RIGHT
    }
    private val timeProgressBar = TimeProgressBar()

    // Ticket column labels
    private val ticketHeaderLabel = JBLabel("TICKET BREAKDOWN").apply {
        font = font.deriveFont(Font.BOLD, JBUI.scale(10).toFloat())
        foreground = StatusColors.SECONDARY_TEXT
    }
    private val ticketValueLabel = JBLabel("").apply {
        font = font.deriveFont(JBUI.scale(10).toFloat())
        foreground = StatusColors.SECONDARY_TEXT
        horizontalAlignment = JBLabel.RIGHT
    }
    private val ticketBar = TicketBreakdownBar()

    init {
        layout = GridLayout(1, 2, JBUI.scale(16), 0)
        isOpaque = false
        border = JBUI.Borders.empty(4, 0)

        // Left column: Time Elapsed
        val timeColumn = JPanel(BorderLayout(0, JBUI.scale(4))).apply {
            isOpaque = false
        }
        val timeLabelsRow = JPanel(BorderLayout()).apply {
            isOpaque = false
        }
        timeLabelsRow.add(timeHeaderLabel, BorderLayout.WEST)
        timeLabelsRow.add(timeValueLabel, BorderLayout.EAST)
        timeColumn.add(timeLabelsRow, BorderLayout.NORTH)
        timeProgressBar.preferredSize = Dimension(0, JBUI.scale(4))
        timeColumn.add(timeProgressBar, BorderLayout.CENTER)
        add(timeColumn)

        // Right column: Ticket Breakdown
        val ticketColumn = JPanel(BorderLayout(0, JBUI.scale(4))).apply {
            isOpaque = false
        }
        val ticketLabelsRow = JPanel(BorderLayout()).apply {
            isOpaque = false
        }
        ticketLabelsRow.add(ticketHeaderLabel, BorderLayout.WEST)
        ticketLabelsRow.add(ticketValueLabel, BorderLayout.EAST)
        ticketColumn.add(ticketLabelsRow, BorderLayout.NORTH)
        ticketBar.preferredSize = Dimension(0, JBUI.scale(4))
        ticketColumn.add(ticketBar, BorderLayout.CENTER)
        add(ticketColumn)
    }

    /**
     * Update the bar with current sprint data and ticket counts.
     */
    fun update(sprint: JiraSprint?, doneCount: Int, inProgressCount: Int, todoCount: Int) {
        val total = doneCount + inProgressCount + todoCount

        // -- Time remaining --
        if (sprint != null && sprint.endDate != null) {
            val endDate = parseIsoDate(sprint.endDate)
            val startDate = sprint.startDate?.let { parseIsoDate(it) }
            val now = LocalDate.now()

            if (endDate != null) {
                val daysRemaining = ChronoUnit.DAYS.between(now, endDate)

                timeValueLabel.text = when {
                    daysRemaining < 0 -> "Overdue by ${-daysRemaining}d"
                    daysRemaining == 0L -> "Ends today"
                    daysRemaining <= 7 -> "${daysRemaining}d left"
                    else -> "Ends ${endDate.format(DateTimeFormatter.ofPattern("MMM d"))}"
                }

                val urgencyColor = when {
                    daysRemaining < 1 -> StatusColors.ERROR
                    daysRemaining < 2 -> StatusColors.WARNING
                    else -> StatusColors.SUCCESS
                }
                timeValueLabel.foreground = urgencyColor

                if (startDate != null) {
                    val totalDays = ChronoUnit.DAYS.between(startDate, endDate).toFloat()
                    val elapsed = ChronoUnit.DAYS.between(startDate, now).toFloat()
                    timeProgressBar.progress = if (totalDays > 0) (elapsed / totalDays).coerceIn(0f, 1f) else 1f
                    timeProgressBar.urgencyColor = urgencyColor
                } else {
                    timeProgressBar.progress = 0f
                    timeProgressBar.urgencyColor = StatusColors.INFO
                }

                val startStr = sprint.startDate?.take(10) ?: "?"
                val endStr = sprint.endDate.take(10)
                toolTipText = "Sprint: ${sprint.name} | Started: $startStr | Ends: $endStr | $doneCount/$total tickets done"
            } else {
                setNoTimeInfo(sprint)
            }
        } else {
            timeValueLabel.text = if (sprint != null) sprint.name else ""
            timeValueLabel.foreground = StatusColors.SECONDARY_TEXT
            timeProgressBar.progress = 0f
            timeProgressBar.urgencyColor = StatusColors.INFO
            toolTipText = null
        }

        // -- Ticket breakdown --
        ticketBar.update(doneCount, inProgressCount, todoCount)
        if (total > 0) {
            ticketValueLabel.text = "$doneCount Done / $inProgressCount In-progress / $todoCount To-do"
        } else {
            ticketValueLabel.text = ""
        }

        timeProgressBar.repaint()
        ticketBar.repaint()
    }

    /**
     * Update from a list of issues (convenience method).
     */
    fun updateFromIssues(sprint: JiraSprint?, issues: List<JiraIssue>) {
        val done = issues.count { it.fields.status.statusCategory?.key == "done" }
        val inProgress = issues.count { it.fields.status.statusCategory?.key == "indeterminate" }
        val todo = issues.size - done - inProgress
        update(sprint, done, inProgress, todo)
    }

    private fun setNoTimeInfo(sprint: JiraSprint) {
        timeValueLabel.text = sprint.name
        timeValueLabel.foreground = StatusColors.SECONDARY_TEXT
        timeProgressBar.progress = 0f
        timeProgressBar.urgencyColor = StatusColors.INFO
        toolTipText = "Sprint: ${sprint.name}"
    }

    private fun parseIsoDate(iso: String): LocalDate? {
        return try {
            OffsetDateTime.parse(iso, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toLocalDate()
        } catch (_: Exception) {
            try {
                LocalDate.parse(iso.take(10))
            } catch (_: Exception) {
                null
            }
        }
    }

    // ---------------------------------------------------------------
    // Time progress bar (thin, single-color)
    // ---------------------------------------------------------------

    private class TimeProgressBar : JPanel() {
        var progress: Float = 0f
        var urgencyColor: Color = StatusColors.INFO

        init {
            isOpaque = false
            preferredSize = Dimension(0, JBUI.scale(4))
            minimumSize = Dimension(0, JBUI.scale(4))
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            val barHeight = height.toFloat()
            val barWidth = width.toFloat()
            val cornerRadius = barHeight / 2

            g2.color = PROGRESS_BG
            g2.fill(RoundRectangle2D.Float(0f, 0f, barWidth, barHeight, cornerRadius, cornerRadius))

            if (progress > 0f) {
                val filledWidth = barWidth * progress
                g2.clip = java.awt.geom.Area(
                    RoundRectangle2D.Float(0f, 0f, barWidth, barHeight, cornerRadius, cornerRadius)
                )
                g2.color = urgencyColor
                g2.fillRect(0, 0, filledWidth.toInt().coerceAtLeast(1), height)
            }

            g2.dispose()
        }
    }

    // ---------------------------------------------------------------
    // Ticket breakdown bar (proportional segments)
    // ---------------------------------------------------------------

    private class TicketBreakdownBar : JPanel() {
        private var doneRatio: Float = 0f
        private var inProgressRatio: Float = 0f
        private var todoRatio: Float = 1f

        init {
            isOpaque = false
            preferredSize = Dimension(0, JBUI.scale(4))
            minimumSize = Dimension(0, JBUI.scale(4))
        }

        fun update(done: Int, inProgress: Int, todo: Int) {
            val total = (done + inProgress + todo).toFloat()
            if (total <= 0f) {
                doneRatio = 0f
                inProgressRatio = 0f
                todoRatio = 1f
            } else {
                doneRatio = done / total
                inProgressRatio = inProgress / total
                todoRatio = todo / total
            }
            repaint()
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            val barHeight = height.toFloat()
            val barWidth = width.toFloat()
            val cornerRadius = barHeight / 2

            g2.color = PROGRESS_BG
            g2.fill(RoundRectangle2D.Float(0f, 0f, barWidth, barHeight, cornerRadius, cornerRadius))

            g2.clip = java.awt.geom.Area(
                RoundRectangle2D.Float(0f, 0f, barWidth, barHeight, cornerRadius, cornerRadius)
            )

            var x = 0f

            if (doneRatio > 0f) {
                val segW = barWidth * doneRatio
                g2.color = StatusColors.SUCCESS
                g2.fillRect(x.toInt(), 0, segW.toInt().coerceAtLeast(1), height)
                x += segW
            }

            if (inProgressRatio > 0f) {
                val segW = barWidth * inProgressRatio
                g2.color = StatusColors.LINK
                g2.fillRect(x.toInt(), 0, segW.toInt().coerceAtLeast(1), height)
                x += segW
            }

            if (todoRatio > 0f) {
                val segW = barWidth * todoRatio
                g2.color = StatusColors.INFO
                g2.fillRect(x.toInt(), 0, segW.toInt().coerceAtLeast(1), height)
            }

            g2.dispose()
        }
    }

    companion object {
        private val PROGRESS_BG = JBColor(0xE8EAED, 0x3D4043)
    }
}

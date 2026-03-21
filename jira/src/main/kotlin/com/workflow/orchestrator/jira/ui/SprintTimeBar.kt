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
import javax.swing.BoxLayout
import javax.swing.JPanel

/**
 * Composite bar showing sprint time remaining with urgency coloring
 * and a proportional ticket breakdown (done/in-progress/todo).
 */
class SprintTimeBar : JPanel() {

    private val timeLabel = JBLabel("").apply {
        font = font.deriveFont(JBUI.scale(11).toFloat())
    }
    private val timeProgressBar = TimeProgressBar()
    private val ticketBar = TicketBreakdownBar()
    private val ticketLabel = JBLabel("").apply {
        font = font.deriveFont(JBUI.scale(11).toFloat())
        foreground = StatusColors.SECONDARY_TEXT
    }

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        border = JBUI.Borders.empty(2, 0)

        // Time row: label + thin progress bar
        val timeRow = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyBottom(2)
        }
        timeRow.add(timeLabel, BorderLayout.WEST)
        timeProgressBar.preferredSize = Dimension(0, JBUI.scale(4))
        timeRow.add(timeProgressBar, BorderLayout.SOUTH)
        add(timeRow)

        // Ticket row: proportional bar + label
        val ticketRow = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyTop(2)
        }
        ticketBar.preferredSize = Dimension(0, JBUI.scale(6))
        ticketRow.add(ticketBar, BorderLayout.NORTH)
        ticketRow.add(ticketLabel, BorderLayout.SOUTH)
        add(ticketRow)
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

                // Label text
                timeLabel.text = when {
                    daysRemaining < 0 -> "Sprint overdue by ${-daysRemaining} day${if (-daysRemaining != 1L) "s" else ""}"
                    daysRemaining == 0L -> "Sprint ends today"
                    daysRemaining <= 7 -> "$daysRemaining day${if (daysRemaining != 1L) "s" else ""} remaining"
                    else -> "Sprint ends ${endDate.format(DateTimeFormatter.ofPattern("MMM d"))}"
                }

                // Urgency color
                timeLabel.foreground = when {
                    daysRemaining < 1 -> StatusColors.ERROR
                    daysRemaining < 2 -> StatusColors.WARNING
                    else -> StatusColors.SUCCESS
                }

                // Progress bar: elapsed / total
                if (startDate != null) {
                    val totalDays = ChronoUnit.DAYS.between(startDate, endDate).toFloat()
                    val elapsed = ChronoUnit.DAYS.between(startDate, now).toFloat()
                    timeProgressBar.progress = if (totalDays > 0) (elapsed / totalDays).coerceIn(0f, 1f) else 1f
                    timeProgressBar.urgencyColor = timeLabel.foreground
                } else {
                    timeProgressBar.progress = 0f
                    timeProgressBar.urgencyColor = StatusColors.INFO
                }

                // Tooltip
                val startStr = sprint.startDate?.take(10) ?: "?"
                val endStr = sprint.endDate.take(10)
                toolTipText = "Sprint: ${sprint.name} | Started: $startStr | Ends: $endStr | $doneCount/$total tickets done"
            } else {
                setNoTimeInfo(sprint)
            }
        } else {
            timeLabel.text = if (sprint != null) sprint.name else ""
            timeLabel.foreground = StatusColors.SECONDARY_TEXT
            timeProgressBar.progress = 0f
            timeProgressBar.urgencyColor = StatusColors.INFO
            toolTipText = null
        }

        // -- Ticket breakdown --
        ticketBar.update(doneCount, inProgressCount, todoCount)
        if (total > 0) {
            ticketLabel.text = "$doneCount done \u00B7 $inProgressCount in progress \u00B7 $todoCount to do"
        } else {
            ticketLabel.text = ""
        }

        timeProgressBar.repaint()
        ticketBar.repaint()
    }

    /**
     * Update from a list of issues (convenience method matching old SprintProgressBar API).
     */
    fun updateFromIssues(sprint: JiraSprint?, issues: List<JiraIssue>) {
        val done = issues.count { it.fields.status.statusCategory?.key == "done" }
        val inProgress = issues.count { it.fields.status.statusCategory?.key == "indeterminate" }
        val todo = issues.size - done - inProgress
        update(sprint, done, inProgress, todo)
    }

    private fun setNoTimeInfo(sprint: JiraSprint) {
        timeLabel.text = sprint.name
        timeLabel.foreground = StatusColors.SECONDARY_TEXT
        timeProgressBar.progress = 0f
        timeProgressBar.urgencyColor = StatusColors.INFO
        toolTipText = "Sprint: ${sprint.name}"
    }

    /**
     * Parse an ISO date string (e.g. "2026-03-10T10:00:00.000+05:30") to LocalDate.
     */
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

            // Background
            g2.color = PROGRESS_BG
            g2.fill(RoundRectangle2D.Float(0f, 0f, barWidth, barHeight, cornerRadius, cornerRadius))

            // Filled portion
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
            preferredSize = Dimension(0, JBUI.scale(6))
            minimumSize = Dimension(0, JBUI.scale(6))
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

            // Background
            g2.color = PROGRESS_BG
            g2.fill(RoundRectangle2D.Float(0f, 0f, barWidth, barHeight, cornerRadius, cornerRadius))

            // Clip to rounded rect
            g2.clip = java.awt.geom.Area(
                RoundRectangle2D.Float(0f, 0f, barWidth, barHeight, cornerRadius, cornerRadius)
            )

            var x = 0f

            // Done (green)
            if (doneRatio > 0f) {
                val segW = barWidth * doneRatio
                g2.color = StatusColors.SUCCESS
                g2.fillRect(x.toInt(), 0, segW.toInt().coerceAtLeast(1), height)
                x += segW
            }

            // In-progress (blue)
            if (inProgressRatio > 0f) {
                val segW = barWidth * inProgressRatio
                g2.color = StatusColors.LINK
                g2.fillRect(x.toInt(), 0, segW.toInt().coerceAtLeast(1), height)
                x += segW
            }

            // Todo (gray)
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

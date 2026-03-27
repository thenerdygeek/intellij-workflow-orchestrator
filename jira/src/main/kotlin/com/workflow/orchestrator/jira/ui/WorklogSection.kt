package com.workflow.orchestrator.jira.ui

import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.services.JiraService
import com.workflow.orchestrator.core.ui.StatusColors
import com.workflow.orchestrator.jira.service.JiraServiceImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.*
import java.awt.geom.RoundRectangle2D
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import javax.swing.BoxLayout
import javax.swing.JPanel

/**
 * Lazy-loaded worklog summary section for the ticket detail panel.
 *
 * Shows total time logged and a table of the last 5 worklog entries
 * with User, Duration, and Date columns.
 */
class WorklogSection(private val project: Project) : JPanel(BorderLayout()) {

    private val log = Logger.getInstance(WorklogSection::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        isOpaque = false
    }

    fun loadWorklogs(issueKey: String) {
        removeAll()

        val loadingPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
            isOpaque = false
            add(JBLabel(AnimatedIcon.Default()).apply {
                border = JBUI.Borders.empty(8)
            })
            add(JBLabel("Loading worklogs...").apply {
                foreground = StatusColors.SECONDARY_TEXT
            })
        }
        add(loadingPanel, BorderLayout.CENTER)
        revalidate()
        repaint()

        scope.launch {
            val jiraServiceImpl = JiraServiceImpl.getInstance(project)
            val apiClient = jiraServiceImpl.getApiClient()

            if (apiClient == null) {
                withContext(Dispatchers.EDT) {
                    showMessage("Jira not configured.")
                }
                return@launch
            }

            val result = apiClient.getWorklogs(issueKey)

            withContext(Dispatchers.EDT) {
                when (result) {
                    is ApiResult.Success -> {
                        val worklogs = result.data.worklogs
                        if (worklogs.isEmpty()) {
                            showMessage("No time logged.")
                        } else {
                            renderWorklogs(worklogs)
                        }
                    }
                    is ApiResult.Error -> {
                        log.warn("[Jira:UI] Failed to load worklogs for $issueKey: ${result.message}")
                        showMessage("Could not load worklogs.")
                    }
                }
            }
        }
    }

    private fun showMessage(text: String) {
        removeAll()
        add(JBLabel(text).apply {
            foreground = StatusColors.SECONDARY_TEXT
            border = JBUI.Borders.empty(8)
        }, BorderLayout.CENTER)
        revalidate()
        repaint()
    }

    private fun renderWorklogs(worklogs: List<com.workflow.orchestrator.jira.api.dto.JiraWorklog>) {
        removeAll()

        val container = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }

        // Total time badge
        val totalSeconds = worklogs.sumOf { it.timeSpentSeconds }
        val totalRow = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(4, 8, 8, 8)
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(24))
        }
        totalRow.add(JBLabel("Total: ${formatTimeSpent(totalSeconds)}").apply {
            font = font.deriveFont(Font.BOLD, JBUI.scale(12).toFloat())
        }, BorderLayout.WEST)
        container.add(totalRow)

        // Table container with border
        val tablePanel = object : JPanel() {
            init {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                border = JBUI.Borders.empty(0, 8, 4, 8)
                alignmentX = Component.LEFT_ALIGNMENT
            }
        }

        // Table header row
        val headerRow = createTableRow("User", "Duration", "Date", isHeader = true)
        tablePanel.add(headerRow)

        // Data rows (last 5, most recent first)
        val recentWorklogs = worklogs.sortedByDescending { it.started }.take(5)
        for (worklog in recentWorklogs) {
            val authorName = worklog.author?.displayName ?: "Unknown"
            val timeStr = formatTimeSpent(worklog.timeSpentSeconds)
            val dateStr = formatStartedDate(worklog.started)
            tablePanel.add(createTableRow(authorName, timeStr, dateStr, isHeader = false))

            // Comment sub-row (if worklog has a comment)
            val comment = worklog.comment?.trim().orEmpty()
            if (comment.isNotBlank()) {
                val commentText = if (comment.length > 80) "${comment.take(80)}\u2026" else comment
                val commentRow = JPanel(BorderLayout()).apply {
                    isOpaque = false
                    alignmentX = Component.LEFT_ALIGNMENT
                    maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(18))
                    border = JBUI.Borders.empty(0, 16, 2, 8)
                }
                commentRow.add(JBLabel(commentText).apply {
                    font = font.deriveFont(Font.ITALIC, JBUI.scale(10).toFloat())
                    foreground = StatusColors.SECONDARY_TEXT
                }, BorderLayout.WEST)
                tablePanel.add(commentRow)
            }
        }

        container.add(tablePanel)
        add(container, BorderLayout.CENTER)
        revalidate()
        repaint()
    }

    private fun createTableRow(col1: String, col2: String, col3: String, isHeader: Boolean): JPanel {
        return object : JPanel(GridLayout(1, 3, JBUI.scale(4), 0)) {
            init {
                isOpaque = false
                alignmentX = Component.LEFT_ALIGNMENT
                maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(if (isHeader) 24 else 28))
                border = JBUI.Borders.empty(4, 8)
            }

            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                if (isHeader) {
                    val g2 = g.create() as Graphics2D
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    g2.color = TABLE_HEADER_BG
                    g2.fill(RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(),
                        JBUI.scale(4).toFloat(), JBUI.scale(4).toFloat()))
                    g2.dispose()
                }
                // Draw bottom separator for data rows
                if (!isHeader) {
                    val g2 = g.create() as Graphics2D
                    g2.color = SEPARATOR_COLOR
                    g2.fillRect(JBUI.scale(8), height - 1, width - JBUI.scale(16), 1)
                    g2.dispose()
                }
            }
        }.apply {
            val fontStyle = if (isHeader) Font.BOLD else Font.PLAIN
            val fontSize = if (isHeader) JBUI.scale(10).toFloat() else JBUI.scale(11).toFloat()
            val color = if (isHeader) StatusColors.SECONDARY_TEXT else JBColor.foreground()
            val col2Color = if (isHeader) StatusColors.SECONDARY_TEXT else StatusColors.SECONDARY_TEXT
            val col3Color = if (isHeader) StatusColors.SECONDARY_TEXT else StatusColors.SECONDARY_TEXT

            add(JBLabel(if (isHeader) col1.uppercase() else col1).apply {
                font = font.deriveFont(fontStyle, fontSize)
                foreground = color
            })
            add(JBLabel(if (isHeader) col2.uppercase() else col2).apply {
                font = font.deriveFont(fontStyle, fontSize)
                foreground = col2Color
            })
            add(JBLabel(if (isHeader) col3.uppercase() else col3).apply {
                font = font.deriveFont(if (isHeader) Font.BOLD else Font.ITALIC, fontSize)
                foreground = col3Color
            })
        }
    }

    fun dispose() {
        scope.cancel()
    }

    companion object {
        private val TABLE_HEADER_BG = JBColor(0xF0F1F3, 0x25282C)
        private val SEPARATOR_COLOR = JBColor(0xE8EAED, 0x2D3035)

        internal fun formatTimeSpent(totalSeconds: Long): String {
            val totalMinutes = totalSeconds / 60
            val totalHours = totalMinutes / 60
            val remainingMinutes = totalMinutes % 60

            return when {
                totalMinutes < 60 -> "${totalMinutes}m"
                totalHours < 8 -> {
                    if (remainingMinutes > 0) "${totalHours}h ${remainingMinutes}m"
                    else "${totalHours}h"
                }
                else -> {
                    val days = totalHours / 8
                    val remainingHours = totalHours % 8
                    if (remainingHours > 0) "${days}d ${remainingHours}h"
                    else "${days}d"
                }
            }
        }

        internal fun formatStartedDate(isoDate: String): String {
            return try {
                val parsed = ZonedDateTime.parse(isoDate)
                val date = parsed.toLocalDate()
                val currentYear = LocalDate.now().year
                if (date.year == currentYear) {
                    date.format(DateTimeFormatter.ofPattern("MMM d"))
                } else {
                    date.format(DateTimeFormatter.ofPattern("MMM d, yyyy"))
                }
            } catch (_: DateTimeParseException) {
                try {
                    val date = LocalDate.parse(isoDate.take(10))
                    val currentYear = LocalDate.now().year
                    if (date.year == currentYear) {
                        date.format(DateTimeFormatter.ofPattern("MMM d"))
                    } else {
                        date.format(DateTimeFormatter.ofPattern("MMM d, yyyy"))
                    }
                } catch (_: Exception) {
                    isoDate.take(10)
                }
            }
        }
    }
}

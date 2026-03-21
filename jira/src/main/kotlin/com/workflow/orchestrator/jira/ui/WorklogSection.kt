package com.workflow.orchestrator.jira.ui

import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.ui.AnimatedIcon
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
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import javax.swing.BoxLayout
import javax.swing.JPanel

/**
 * Lazy-loaded worklog summary section for the ticket detail panel.
 *
 * Shows total time logged and the last 5 worklog entries with author,
 * time spent, date, and optional comment.
 */
class WorklogSection(private val project: Project) : JPanel(BorderLayout()) {

    private val log = Logger.getInstance(WorklogSection::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        isOpaque = false
    }

    fun loadWorklogs(issueKey: String) {
        removeAll()

        // Show loading placeholder
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

        // Total time logged
        val totalSeconds = worklogs.sumOf { it.timeSpentSeconds }
        val totalLabel = JBLabel("Total: ${formatTimeSpent(totalSeconds)}").apply {
            font = font.deriveFont(Font.BOLD, JBUI.scale(12).toFloat())
            border = JBUI.Borders.empty(4, 8)
            alignmentX = Component.LEFT_ALIGNMENT
        }
        container.add(totalLabel)

        // Last 5 worklogs (most recent first)
        val recentWorklogs = worklogs.sortedByDescending { it.started }.take(5)
        for (worklog in recentWorklogs) {
            container.add(createWorklogEntry(worklog))
        }

        add(container, BorderLayout.CENTER)
        revalidate()
        repaint()
    }

    private fun createWorklogEntry(worklog: com.workflow.orchestrator.jira.api.dto.JiraWorklog): JPanel {
        val entryPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(4, 8)
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
        }

        // Main row: "John Doe logged 2h 30m on Mar 15"
        val authorName = worklog.author?.displayName ?: "Unknown"
        val timeStr = formatTimeSpent(worklog.timeSpentSeconds)
        val dateStr = formatStartedDate(worklog.started)

        val mainRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
        }
        mainRow.add(JBLabel(authorName).apply {
            font = font.deriveFont(Font.BOLD, JBUI.scale(11).toFloat())
        })
        mainRow.add(JBLabel("logged $timeStr on $dateStr").apply {
            font = font.deriveFont(JBUI.scale(11).toFloat())
            foreground = StatusColors.SECONDARY_TEXT
        })
        entryPanel.add(mainRow)

        // Comment row (optional, dimmed, truncated)
        val comment = worklog.comment?.trim()
        if (!comment.isNullOrBlank()) {
            val truncatedComment = if (comment.length > 80) {
                comment.substring(0, 79) + "\u2026"
            } else {
                comment
            }
            val commentLabel = JBLabel(truncatedComment).apply {
                font = font.deriveFont(JBUI.scale(10).toFloat())
                foreground = StatusColors.SECONDARY_TEXT
                border = JBUI.Borders.emptyLeft(JBUI.scale(4))
                alignmentX = Component.LEFT_ALIGNMENT
                if (comment.length > 80) toolTipText = comment
            }
            entryPanel.add(commentLabel)
        }

        return entryPanel
    }

    fun dispose() {
        scope.cancel()
    }

    companion object {
        /**
         * Format seconds into human-readable time:
         * - < 60 min: "Xm"
         * - < 8h: "Xh Ym"
         * - >= 8h: "Xd Yh" (8h = 1 workday)
         */
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

        /**
         * Parse ISO date string and format as "Mar 15" (same year) or "Mar 15, 2025" (different year).
         */
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
                // Fallback: try parsing just date portion
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

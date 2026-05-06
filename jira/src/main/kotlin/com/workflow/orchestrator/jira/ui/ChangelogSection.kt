package com.workflow.orchestrator.jira.ui

import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.core.model.jira.TicketHistoryEntry
import com.workflow.orchestrator.core.services.JiraService
import com.workflow.orchestrator.core.ui.StatusColors
import com.workflow.orchestrator.core.ui.TimeFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BoxLayout
import javax.swing.JPanel

/**
 * Lazy-loaded changelog feed showing the last few ticket-history rows.
 *
 * Mirrors [WorklogSection]: own background scope, flips between
 * loading / data / empty / error states on the EDT.
 */
class ChangelogSection(private val project: Project) : JPanel(BorderLayout()) {

    private val log = Logger.getInstance(ChangelogSection::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        isOpaque = false
    }

    fun loadHistory(issueKey: String) {
        removeAll()
        val loadingPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
            isOpaque = false
            add(JBLabel(AnimatedIcon.Default()).apply { border = JBUI.Borders.empty(8) })
            add(JBLabel("Loading history...").apply { foreground = StatusColors.SECONDARY_TEXT })
        }
        add(loadingPanel, BorderLayout.CENTER)
        revalidate()
        repaint()

        scope.launch {
            val service = project.getService(JiraService::class.java)
            val result = service.getTicketHistory(issueKey)

            withContext(Dispatchers.EDT) {
                if (result.isError) {
                    log.warn("[Jira:UI] Failed to load history for $issueKey: ${result.summary}")
                    showMessage("Could not load history.")
                } else if (result.data.isEmpty()) {
                    showMessage("No history yet.")
                } else {
                    renderEntries(result.data)
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

    private fun renderEntries(entries: List<TicketHistoryEntry>) {
        removeAll()

        // Newest first; Jira returns oldest-first in the flattened changelog.
        val sorted = entries.sortedByDescending { it.createdAt }

        val container = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(2, 8)
        }

        val initialVisible = sorted.take(MAX_VISIBLE)
        initialVisible.forEach { container.add(buildRow(it)) }

        if (sorted.size > MAX_VISIBLE) {
            val remaining = sorted.drop(MAX_VISIBLE)
            val showAllLink = JBLabel("<html><a href=''>Show all (${sorted.size})</a></html>").apply {
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                border = JBUI.Borders.empty(4, 8)
                alignmentX = Component.LEFT_ALIGNMENT
            }
            container.add(showAllLink)
            showAllLink.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent?) {
                    val idx = container.getComponentZOrder(showAllLink)
                    if (idx < 0) return
                    container.remove(showAllLink)
                    remaining.forEach { container.add(buildRow(it)) }
                    container.revalidate()
                    container.repaint()
                }
            })
        }

        add(container, BorderLayout.CENTER)
        revalidate()
        repaint()
    }

    private fun buildRow(entry: TicketHistoryEntry): JPanel {
        val rowText = formatLine(entry)
        return JPanel(BorderLayout()).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(20))
            border = JBUI.Borders.empty(2, 0)
            add(JBLabel(rowText).apply {
                font = font.deriveFont(Font.PLAIN, JBUI.scale(11).toFloat())
                foreground = JBColor.foreground()
            }, BorderLayout.WEST)
        }
    }

    fun dispose() {
        scope.cancel()
    }

    companion object {
        /** Number of entries shown before the "Show all" expander appears. */
        const val MAX_VISIBLE = 8

        /**
         * Pure formatting helper: render one history entry as a single compact line.
         * Status and assignee changes get special phrasing; everything else uses
         * the canonical `"<actor> changed <field> <old>→<new> · <time-ago>"` form.
         */
        fun formatLine(entry: TicketHistoryEntry): String {
            val actor = entry.actorDisplayName.ifBlank { "Someone" }
            val timeAgo = TimeFormatter.relativeFromIso(entry.createdAt)
            val timeSuffix = if (timeAgo.isNotBlank()) " · $timeAgo" else ""
            val fieldLower = entry.field.lowercase()
            val newVal = entry.newValue?.takeIf { it.isNotBlank() }
            val oldVal = entry.oldValue?.takeIf { it.isNotBlank() }
            return when (fieldLower) {
                "status" -> {
                    if (newVal != null) "$actor set status to $newVal$timeSuffix"
                    else "$actor cleared status$timeSuffix"
                }
                "assignee" -> {
                    if (newVal != null) "$actor assigned to $newVal$timeSuffix"
                    else "$actor unassigned the issue$timeSuffix"
                }
                else -> {
                    val arrow = when {
                        oldVal != null && newVal != null -> "$oldVal→$newVal"
                        newVal != null -> newVal
                        oldVal != null -> "$oldVal→(none)"
                        else -> ""
                    }
                    val arrowPart = if (arrow.isNotBlank()) " $arrow" else ""
                    "$actor changed ${entry.field}$arrowPart$timeSuffix"
                }
            }
        }
    }
}

package com.workflow.orchestrator.automation.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.workflow.orchestrator.automation.model.QueueEntry
import com.workflow.orchestrator.automation.model.QueueEntryStatus
import com.workflow.orchestrator.automation.service.QueueService
import com.workflow.orchestrator.core.ui.StatusColors
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.BoxLayout
import javax.swing.JPanel

/**
 * Read-only queue status indicator. Mirrors the user's selection in [MonitorPanel]
 * when one exists; otherwise falls back to the most-actionable queue entry
 * (running > queued-on-bamboo > waiting). The previous Cancel button was removed
 * in PR 8 — Cancel/Remove now live on the per-row detail panel where the target
 * is unambiguous.
 *
 * Wiring: the parent [AutomationPanel] calls [setSelection] from
 * `MonitorPanel.onSelectionChanged`. When [setSelection] receives null, the panel
 * falls back to its own `pickActiveEntry` over [QueueService.stateFlow].
 */
class QueueStatusPanel(
    private val project: Project
) : JPanel(BorderLayout()), Disposable {

    private val log = Logger.getInstance(QueueStatusPanel::class.java)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val statusDot = JBLabel("●").apply { foreground = JBColor.GRAY }
    private val statusLabel = JBLabel("Queue idle.").apply {
        foreground = StatusColors.SECONDARY_TEXT
    }

    /** Latest entries we received from [QueueService.stateFlow] — used by the
     *  fallback path when the user hasn't selected anything in the Monitor list. */
    @Volatile
    private var lastEntries: List<QueueEntry> = emptyList()

    /** ID of the entry the user has highlighted in the MonitorPanel list, or null
     *  if no row is selected. Drives the rendered status (PR 8 #4). */
    @Volatile
    private var selectedEntryId: String? = null

    init {
        border = JBUI.Borders.emptyBottom(8)

        val headerLabel = JBLabel("QUEUE STATUS").apply {
            font = font.deriveFont(Font.BOLD, JBUI.scale(10).toFloat())
            foreground = StatusColors.SECONDARY_TEXT
            border = JBUI.Borders.emptyBottom(4)
        }

        val statusBar = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
            add(statusDot)
            add(statusLabel)
        }

        val topPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(headerLabel)
            add(statusBar)
        }

        add(topPanel, BorderLayout.NORTH)

        subscribeToQueue()
    }

    /**
     * Called by [AutomationPanel] when the user changes selection in
     * [MonitorPanel]. Pass `null` when no row is selected — the panel will fall
     * back to its own active-entry rule.
     */
    fun setSelection(entryId: String?) {
        selectedEntryId = entryId
        // Rerender against the latest cached entries on the EDT.
        scope.launch {
            withContext(Dispatchers.EDT) { rerender() }
        }
    }

    private fun subscribeToQueue() {
        scope.launch {
            try {
                val queueService = project.getService(QueueService::class.java) ?: return@launch
                queueService.stateFlow.collectLatest { entries ->
                    lastEntries = entries
                    withContext(Dispatchers.EDT) { rerender() }
                }
            } catch (_: CancellationException) {
                // Expected on dispose
            }
        }
    }

    private fun rerender() {
        val entries = lastEntries
        val target = entries.firstOrNull { it.id == selectedEntryId }
            ?: pickActiveEntry(entries)
        val rendered = renderState(entries, target)
        statusLabel.text = rendered.text
        statusLabel.foreground = rendered.foreground
        statusDot.foreground = rendered.dotColor
    }

    private fun pickActiveEntry(entries: List<QueueEntry>): QueueEntry? {
        // Prefer something we can actually act on — running > queued-on-bamboo > waiting.
        return entries.firstOrNull { it.status == QueueEntryStatus.RUNNING }
            ?: entries.firstOrNull { it.status == QueueEntryStatus.QUEUED_ON_BAMBOO }
            ?: entries.firstOrNull { it.status == QueueEntryStatus.WAITING_LOCAL }
            ?: entries.firstOrNull { it.status !in TERMINAL_STATUSES }
    }

    private fun renderState(entries: List<QueueEntry>, active: QueueEntry?): RenderedStatus {
        if (active == null) {
            return RenderedStatus(
                text = if (entries.isEmpty()) "Queue idle." else "All entries terminal.",
                foreground = StatusColors.SECONDARY_TEXT,
                dotColor = JBColor.GRAY
            )
        }
        val depth = entries.count { it.status !in TERMINAL_STATUSES }
        val statusBlurb = when (active.status) {
            QueueEntryStatus.RUNNING -> "Running"
            QueueEntryStatus.QUEUED_ON_BAMBOO -> "Queued on Bamboo"
            QueueEntryStatus.WAITING_LOCAL -> "Waiting (local)"
            QueueEntryStatus.TRIGGERING -> "Triggering"
            QueueEntryStatus.COMPLETED -> "Completed"
            QueueEntryStatus.FAILED -> "Failed"
            QueueEntryStatus.FAILED_TO_TRIGGER -> "Failed to trigger"
            QueueEntryStatus.CANCELLED -> "Cancelled"
            else -> active.status.name
        }
        // Depth suffix only makes sense for live queue activity. Hide it when
        // the user has selected a terminal row — they're inspecting a finished
        // run, not the queue depth.
        val depthSuffix = if (depth > 1 && active.status !in TERMINAL_STATUSES) " — $depth in queue" else ""
        return RenderedStatus(
            text = "$statusBlurb: ${active.suitePlanKey}$depthSuffix",
            foreground = if (active.status in TERMINAL_STATUSES) StatusColors.SECONDARY_TEXT else StatusColors.LINK,
            dotColor = when (active.status) {
                QueueEntryStatus.RUNNING -> StatusColors.SUCCESS
                QueueEntryStatus.QUEUED_ON_BAMBOO, QueueEntryStatus.WAITING_LOCAL,
                QueueEntryStatus.TRIGGERING -> StatusColors.WARNING
                QueueEntryStatus.COMPLETED -> StatusColors.SUCCESS
                QueueEntryStatus.FAILED, QueueEntryStatus.FAILED_TO_TRIGGER -> StatusColors.ERROR
                else -> JBColor.GRAY
            }
        )
    }

    override fun dispose() {
        scope.cancel()
    }

    private data class RenderedStatus(
        val text: String,
        val foreground: java.awt.Color,
        val dotColor: java.awt.Color
    )

    private companion object {
        /** Delegates to the canonical set on the enum — single source of truth. */
        private val TERMINAL_STATUSES = QueueEntryStatus.TERMINAL
    }
}

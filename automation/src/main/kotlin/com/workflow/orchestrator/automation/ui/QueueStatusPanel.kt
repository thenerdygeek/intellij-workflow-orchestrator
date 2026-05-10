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
import javax.swing.JButton
import javax.swing.JPanel

/**
 * Live queue status indicator with a contextual Cancel action.
 *
 * Subscribes to [QueueService.stateFlow] and reflects the current queue: idle when
 * empty, otherwise the active entry's plan key + status with the queue depth and
 * a Cancel button targeting the active entry. Trigger Now / Queue Run live on the
 * parent panel header — this panel is read-only state + cancel only (A-P1-2).
 */
class QueueStatusPanel(
    private val project: Project
) : JPanel(BorderLayout()), Disposable {

    private val log = Logger.getInstance(QueueStatusPanel::class.java)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val cancelButton = JButton("Cancel").apply {
        isEnabled = false
        isFocusPainted = false
        toolTipText = "No active queue entry"
    }

    private val statusDot = JBLabel("●").apply { foreground = JBColor.GRAY }
    private val statusLabel = JBLabel("Queue idle.").apply {
        foreground = StatusColors.SECONDARY_TEXT
    }

    // Latest entry the Cancel button targets; null when idle.
    @Volatile
    private var activeEntryId: String? = null

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

        val actionBar = JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
            add(cancelButton)
        }

        cancelButton.addActionListener { onCancelClicked() }

        val topPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(headerLabel)
            add(statusBar)
        }

        add(topPanel, BorderLayout.NORTH)
        add(actionBar, BorderLayout.SOUTH)

        subscribeToQueue()
    }

    private fun subscribeToQueue() {
        scope.launch {
            try {
                val queueService = project.getService(QueueService::class.java) ?: return@launch
                queueService.stateFlow.collectLatest { entries ->
                    val active = pickActiveEntry(entries)
                    activeEntryId = active?.id
                    val rendered = renderState(entries, active)
                    withContext(Dispatchers.EDT) {
                        statusLabel.text = rendered.text
                        statusLabel.foreground = rendered.foreground
                        statusDot.foreground = rendered.dotColor
                        cancelButton.isEnabled = active != null
                        cancelButton.toolTipText = if (active != null) {
                            "Cancel ${active.suitePlanKey} (${active.status.name})"
                        } else {
                            "No active queue entry"
                        }
                    }
                }
            } catch (_: CancellationException) {
                // Expected on dispose
            }
        }
    }

    private fun onCancelClicked() {
        val entryId = activeEntryId ?: return
        log.info("[Automation:QueueStatusPanel] Cancel requested for entry $entryId")
        project.getService(QueueService::class.java)?.cancel(entryId)
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
            else -> active.status.name
        }
        val depthSuffix = if (depth > 1) " — $depth in queue" else ""
        return RenderedStatus(
            text = "$statusBlurb: ${active.suitePlanKey}$depthSuffix",
            foreground = StatusColors.LINK,
            dotColor = when (active.status) {
                QueueEntryStatus.RUNNING -> StatusColors.SUCCESS
                QueueEntryStatus.QUEUED_ON_BAMBOO, QueueEntryStatus.WAITING_LOCAL,
                QueueEntryStatus.TRIGGERING -> StatusColors.WARNING
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

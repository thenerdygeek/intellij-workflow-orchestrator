package com.workflow.orchestrator.automation.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.workflow.orchestrator.core.ui.StatusColors
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.automation.model.QueueEntryStatus
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.*

class QueueStatusPanel(
    private val project: Project
) : JPanel(BorderLayout()), Disposable {

    private val statusLabel = JBLabel("Suite Idle").apply {
        foreground = StatusColors.SECONDARY_TEXT
    }
    private val positionLabel = JBLabel("").apply {
        font = Font(Font.MONOSPACED, Font.PLAIN, font.size)
    }
    private val estimateLabel = JBLabel("").apply {
        foreground = StatusColors.SECONDARY_TEXT
    }
    private val alertLabel = JBLabel("").apply { isVisible = false }

    private val cancelButton = JButton("Cancel").apply {
        isEnabled = false
        isFocusPainted = false
    }
    private val queueButton = JButton("Queue Run").apply {
        isFocusPainted = false
    }
    private val triggerButton = JButton("Trigger Now \u25B6").apply {
        isFocusPainted = false
    }

    var onCancel: (() -> Unit)? = null
    var onQueue: (() -> Unit)? = null
    var onTriggerNow: (() -> Unit)? = null

    init {
        border = JBUI.Borders.emptyBottom(8)

        // Stitch: uppercase section header
        val headerLabel = JBLabel("QUEUE STATUS").apply {
            font = font.deriveFont(Font.BOLD, JBUI.scale(10).toFloat())
            foreground = StatusColors.SECONDARY_TEXT
            border = JBUI.Borders.emptyBottom(4)
        }

        val statusBar = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
            add(JBLabel("\u25CF").apply { foreground = JBColor.GRAY })
            add(statusLabel)
            add(positionLabel)
            add(estimateLabel)
        }

        alertLabel.apply {
            border = JBUI.Borders.empty(4, 8)
            foreground = StatusColors.WARNING
        }

        val actionBar = JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
            add(cancelButton)
            add(queueButton)
            add(triggerButton)
        }

        cancelButton.addActionListener { onCancel?.invoke() }
        queueButton.addActionListener { onQueue?.invoke() }
        triggerButton.addActionListener { onTriggerNow?.invoke() }

        val topPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(headerLabel)
            add(statusBar)
            add(alertLabel)
        }

        add(topPanel, BorderLayout.NORTH)
        add(actionBar, BorderLayout.SOUTH)
    }

    fun updateStatus(
        status: QueueEntryStatus?,
        queuePosition: Int = -1,
        estimatedWaitMs: Long? = null,
        runnerName: String? = null
    ) {
        statusLabel.text = when (status) {
            QueueEntryStatus.WAITING_LOCAL -> "Waiting for suite..."
            QueueEntryStatus.TRIGGERING -> "Triggering..."
            QueueEntryStatus.QUEUED_ON_BAMBOO -> "Queued on Bamboo"
            QueueEntryStatus.RUNNING -> "Running" + (runnerName?.let { " — $it" } ?: "")
            QueueEntryStatus.COMPLETED -> "Completed"
            QueueEntryStatus.FAILED_TO_TRIGGER -> "Failed to trigger"
            QueueEntryStatus.TAG_INVALID -> "Tags invalid"
            null -> "Suite Idle"
            else -> status.name
        }

        positionLabel.text = if (queuePosition >= 0) "Queue: #${queuePosition + 1}" else ""

        estimateLabel.text = if (estimatedWaitMs != null && estimatedWaitMs > 0) {
            val minutes = estimatedWaitMs / 60_000
            "Est. ~${minutes} min"
        } else ""

        cancelButton.isEnabled = status in listOf(
            QueueEntryStatus.WAITING_LOCAL,
            QueueEntryStatus.QUEUED_ON_BAMBOO
        )
    }

    fun showAlert(message: String) {
        alertLabel.text = "\u26A0 $message"
        alertLabel.isVisible = true
    }

    fun hideAlert() {
        alertLabel.isVisible = false
    }

    override fun dispose() {}
}

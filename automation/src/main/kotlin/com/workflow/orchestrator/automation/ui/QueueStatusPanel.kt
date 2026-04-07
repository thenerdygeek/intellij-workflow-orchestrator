package com.workflow.orchestrator.automation.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.workflow.orchestrator.core.ui.StatusColors
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.*

class QueueStatusPanel(
    private val project: Project
) : JPanel(BorderLayout()), Disposable {

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
            add(JBLabel("Suite Idle").apply { foreground = StatusColors.SECONDARY_TEXT })
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
        }

        add(topPanel, BorderLayout.NORTH)
        add(actionBar, BorderLayout.SOUTH)
    }

    override fun dispose() {}
}

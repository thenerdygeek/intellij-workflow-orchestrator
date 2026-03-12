package com.workflow.orchestrator.handover.ui.panels

import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.JButton
import javax.swing.JPanel

class TimeLogPanel(private val project: Project) : JPanel(BorderLayout()) {

    val ticketLabel = JBLabel("")
    val dateField = JBTextField(10)
    val hoursField = JBTextField("1.0", 5)
    private val decrementButton = JButton("-").apply {
        addActionListener {
            val current = hoursField.text.toDoubleOrNull() ?: 1.0
            hoursField.text = (current - 0.5).coerceAtLeast(0.5).toString()
        }
    }
    private val incrementButton = JButton("+").apply {
        addActionListener {
            val current = hoursField.text.toDoubleOrNull() ?: 1.0
            hoursField.text = (current + 0.5).coerceAtMost(7.0).toString()
        }
    }
    val commentField = JBTextField()
    val elapsedHintLabel = JBLabel("")
    val logButton = JButton("Log Work")
    val statusLabel = JBLabel("")

    init {
        border = JBUI.Borders.empty(8)

        val header = JBLabel("Time Tracking").apply {
            font = font.deriveFont(java.awt.Font.BOLD, 14f)
        }

        val formPanel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            insets = JBUI.insets(4)
        }

        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.0
        formPanel.add(JBLabel("Ticket:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        formPanel.add(ticketLabel, gbc)

        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0.0
        formPanel.add(JBLabel("Date:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        formPanel.add(dateField, gbc)

        gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0.0
        formPanel.add(JBLabel("Hours (max 7):"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        val hoursStepper = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 2, 0)).apply {
            add(decrementButton)
            add(hoursField)
            add(incrementButton)
        }
        formPanel.add(hoursStepper, gbc)

        gbc.gridx = 0; gbc.gridy = 3; gbc.weightx = 0.0
        formPanel.add(JBLabel("Comment:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        formPanel.add(commentField, gbc)

        gbc.gridx = 0; gbc.gridy = 4; gbc.gridwidth = 2; gbc.weightx = 1.0
        formPanel.add(elapsedHintLabel, gbc)

        val southPanel = JPanel(BorderLayout()).apply {
            add(logButton, BorderLayout.WEST)
            add(statusLabel, BorderLayout.EAST)
        }

        add(header, BorderLayout.NORTH)
        add(formPanel, BorderLayout.CENTER)
        add(southPanel, BorderLayout.SOUTH)
    }
}

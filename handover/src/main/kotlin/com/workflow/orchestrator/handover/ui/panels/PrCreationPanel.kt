package com.workflow.orchestrator.handover.ui.panels

import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.JButton
import javax.swing.JPanel

class PrCreationPanel(private val project: Project) : JPanel(BorderLayout()) {

    val titleField = JBTextField()
    val descriptionArea = JBTextArea(8, 40)
    val sourceBranchLabel = JBLabel("")
    val targetBranchLabel = JBLabel("")
    val createButton = JButton("Create PR")
    val regenerateButton = JButton("Regenerate Description")
    val resultLabel = JBLabel("")

    init {
        border = JBUI.Borders.empty(8)

        val formPanel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            insets = JBUI.insets(4)
        }

        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.0
        formPanel.add(JBLabel("Title:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        formPanel.add(titleField, gbc)

        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0.0
        formPanel.add(JBLabel("Source:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        formPanel.add(sourceBranchLabel, gbc)

        gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0.0
        formPanel.add(JBLabel("Target:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        formPanel.add(targetBranchLabel, gbc)

        val buttonPanel = JPanel().apply {
            add(createButton)
            add(regenerateButton)
        }

        add(formPanel, BorderLayout.NORTH)
        add(JBScrollPane(descriptionArea), BorderLayout.CENTER)

        val southPanel = JPanel(BorderLayout()).apply {
            add(buttonPanel, BorderLayout.WEST)
            add(resultLabel, BorderLayout.EAST)
        }
        add(southPanel, BorderLayout.SOUTH)
    }
}

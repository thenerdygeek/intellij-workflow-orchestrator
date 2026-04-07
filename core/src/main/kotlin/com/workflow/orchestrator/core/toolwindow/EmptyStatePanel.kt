package com.workflow.orchestrator.core.toolwindow

import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.SwingConstants

class EmptyStatePanel(
    private val project: Project,
    message: String
) : JPanel(BorderLayout()) {

    init {
        val centerPanel = JPanel(FlowLayout(FlowLayout.CENTER))
        centerPanel.border = JBUI.Borders.emptyTop(50)

        val label = JBLabel(message.replace("\n", "<br>").let { "<html><center>$it</center></html>" })
        label.horizontalAlignment = SwingConstants.CENTER
        label.foreground = JBUI.CurrentTheme.Label.disabledForeground()
        centerPanel.add(label)

        val settingsButton = JButton("Open Settings")
        settingsButton.addActionListener {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, "Workflow Orchestrator")
        }
        val buttonPanel = JPanel(FlowLayout(FlowLayout.CENTER))
        buttonPanel.add(settingsButton)
        add(buttonPanel, BorderLayout.CENTER)
        add(centerPanel, BorderLayout.NORTH)
    }
}

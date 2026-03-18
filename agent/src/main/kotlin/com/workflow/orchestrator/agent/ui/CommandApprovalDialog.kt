package com.workflow.orchestrator.agent.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Shows a shell command for approval before the agent executes it.
 * Displays the command, working directory, and risk assessment.
 */
class CommandApprovalDialog(
    project: Project,
    private val command: String,
    private val workingDir: String,
    private val riskAssessment: String
) : DialogWrapper(project) {

    var approved = false
        private set

    init {
        title = "Agent Command Approval"
        setOKButtonText("Execute")
        setCancelButtonText("Block")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.preferredSize = Dimension(JBUI.scale(600), JBUI.scale(300))
        panel.border = JBUI.Borders.empty(12)

        // Risk label at the top
        val riskLabel = JBLabel("<html><b>Risk:</b> $riskAssessment</html>")
        riskLabel.foreground = JBColor.ORANGE
        riskLabel.border = JBUI.Borders.emptyBottom(8)
        panel.add(riskLabel, BorderLayout.NORTH)

        // Command display — monospaced terminal style
        val cmdArea = JBTextArea().apply {
            text = "Working directory: $workingDir\n\n$ $command"
            isEditable = false
            font = JBUI.Fonts.create("Monospaced", 13)
            border = JBUI.Borders.empty(8)
            background = JBColor(Color(40, 44, 52), Color(40, 44, 52))
            foreground = JBColor(Color(171, 178, 191), Color(171, 178, 191))
        }
        panel.add(JBScrollPane(cmdArea), BorderLayout.CENTER)

        // Warning at the bottom
        val warning = JBLabel("<html><i>The agent wants to execute this command. Review it carefully.</i></html>")
        warning.border = JBUI.Borders.emptyTop(8)
        panel.add(warning, BorderLayout.SOUTH)

        return panel
    }

    override fun doOKAction() {
        approved = true
        super.doOKAction()
    }
}

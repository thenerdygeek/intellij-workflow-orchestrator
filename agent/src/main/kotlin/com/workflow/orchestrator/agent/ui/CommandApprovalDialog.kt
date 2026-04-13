package com.workflow.orchestrator.agent.ui

import com.intellij.icons.AllIcons
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
    private val riskAssessment: String,
    private val allowSessionApproval: Boolean = true
) : DialogWrapper(project) {

    var approved = false
        private set

    var allowAll = false
        private set

    private lateinit var allowAllCheckbox: com.intellij.ui.components.JBCheckBox

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

        // Risk label at the top — color-coded with icon
        val (riskColor, riskIcon) = when (riskAssessment.uppercase()) {
            "HIGH" -> JBColor.RED to AllIcons.General.BalloonError
            "MEDIUM" -> JBColor.ORANGE to AllIcons.General.BalloonWarning
            else -> JBColor.GRAY to AllIcons.General.BalloonInformation
        }
        val riskLabel = JBLabel("<html><b>Risk:</b> $riskAssessment</html>", riskIcon, JBLabel.LEFT)
        riskLabel.foreground = riskColor
        riskLabel.border = JBUI.Borders.emptyBottom(8)
        panel.add(riskLabel, BorderLayout.NORTH)

        // Command display — monospaced terminal style, theme-aware
        val cmdArea = JBTextArea().apply {
            text = "Working directory: $workingDir\n\n$ $command"
            isEditable = false
            font = JBUI.Fonts.create("Monospaced", 13)
            border = JBUI.Borders.empty(8)
            background = JBColor(Color(0xF1F5F9), Color(40, 44, 52))
            foreground = JBColor(Color(0x1E293B), Color(171, 178, 191))
        }
        panel.add(JBScrollPane(cmdArea), BorderLayout.CENTER)

        // Allow all checkbox — hidden for HIGH risk
        allowAllCheckbox = com.intellij.ui.components.JBCheckBox("Allow all commands this session").apply {
            font = JBUI.Fonts.smallFont()
            isVisible = allowSessionApproval && riskAssessment.uppercase() != "HIGH"
        }

        // Warning at the bottom
        val warning = JBLabel("<html><i>The agent wants to execute this command. Review it carefully.</i></html>")
        warning.border = JBUI.Borders.emptyTop(8)

        val bottomPanel = JPanel(BorderLayout())
        bottomPanel.add(allowAllCheckbox, BorderLayout.WEST)
        bottomPanel.add(warning, BorderLayout.CENTER)
        panel.add(bottomPanel, BorderLayout.SOUTH)

        return panel
    }

    override fun doOKAction() {
        approved = true
        allowAll = allowAllCheckbox.isSelected
        super.doOKAction()
    }
}

package com.workflow.orchestrator.handover.ui.panels

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.handover.model.MacroStep
import com.workflow.orchestrator.handover.model.MacroStepStatus
import java.awt.BorderLayout
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JPanel
import javax.swing.SwingConstants

class CompletionMacroPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val stepsPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
    }
    val runButton = JButton("Run Macro")
    val statusLabel = JBLabel("")
    private val checkboxes = mutableMapOf<String, JCheckBox>()

    init {
        border = JBUI.Borders.empty(8)

        val header = JBLabel("Complete Task Macro").apply {
            font = JBUI.Fonts.label().deriveFont(java.awt.Font.BOLD)
        }

        val southPanel = JPanel(BorderLayout()).apply {
            add(runButton, BorderLayout.WEST)
            add(statusLabel, BorderLayout.EAST)
        }

        add(header, BorderLayout.NORTH)
        add(stepsPanel, BorderLayout.CENTER)
        add(southPanel, BorderLayout.SOUTH)
    }

    fun setSteps(steps: List<MacroStep>) {
        stepsPanel.removeAll()
        checkboxes.clear()

        for (step in steps) {
            val checkbox = JCheckBox(step.label, step.enabled)
            checkboxes[step.id] = checkbox

            val icon = when (step.status) {
                MacroStepStatus.PENDING -> null
                MacroStepStatus.RUNNING -> AllIcons.Process.Step_1
                MacroStepStatus.SUCCESS -> AllIcons.General.InspectionsOK
                MacroStepStatus.FAILED -> AllIcons.General.Error
                MacroStepStatus.SKIPPED -> AllIcons.RunConfigurations.TestNotRan
            }

            val row = JPanel(BorderLayout()).apply {
                add(checkbox, BorderLayout.WEST)
                if (icon != null) {
                    add(JBLabel(icon), BorderLayout.EAST)
                }
            }
            stepsPanel.add(row)
        }

        stepsPanel.revalidate()
        stepsPanel.repaint()
    }

    fun getEnabledStepIds(): List<String> {
        return checkboxes.filter { it.value.isSelected }.keys.toList()
    }
}

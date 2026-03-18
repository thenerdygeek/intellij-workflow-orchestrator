package com.workflow.orchestrator.agent.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import javax.swing.*

class AgentDashboardPanel : JPanel(BorderLayout()) {

    private val stepListModel = DefaultListModel<String>()
    private val stepList = JBList(stepListModel)
    private val outputArea = JTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        font = JBUI.Fonts.create("Monospaced", 12)
        border = JBUI.Borders.empty(4)
    }
    private val tokenWidget = TokenBudgetWidget()
    private val completedSteps = mutableSetOf<String>()
    private val currentStep = mutableSetOf<String>()

    val newTaskButton = JButton("New Task").apply {
        icon = AllIcons.General.Add
    }
    val cancelButton = JButton("Cancel").apply {
        icon = AllIcons.Actions.Cancel
        isEnabled = false
    }
    val settingsLink = JBLabel("<html><a href=''>Settings</a></html>").apply {
        cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
    }

    init {
        border = JBUI.Borders.empty(8)

        // North: toolbar
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4))
        toolbar.add(newTaskButton)
        toolbar.add(cancelButton)
        toolbar.add(settingsLink)
        add(toolbar, BorderLayout.NORTH)

        // Center: splitter with step list (left) and output (right)
        stepList.cellRenderer = StepListCellRenderer()
        val splitter = JBSplitter(false, 0.4f)
        splitter.firstComponent = JBScrollPane(stepList)
        splitter.secondComponent = JBScrollPane(outputArea)
        add(splitter, BorderLayout.CENTER)

        // South: token budget widget
        add(tokenWidget, BorderLayout.SOUTH)
    }

    fun showPlan(tasks: List<String>) = runOnEdt {
        stepListModel.clear()
        completedSteps.clear()
        currentStep.clear()
        for (task in tasks) {
            stepListModel.addElement(task)
        }
        outputArea.text = ""
        cancelButton.isEnabled = true
    }

    fun updateProgress(step: String, tokensUsed: Int, maxTokens: Int) = runOnEdt {
        // Mark previous current steps as completed
        completedSteps.addAll(currentStep)
        currentStep.clear()
        currentStep.add(step)

        // Add step to model if not present
        val found = (0 until stepListModel.size()).any { stepListModel.getElementAt(it) == step }
        if (!found) {
            stepListModel.addElement(step)
        }

        stepList.repaint()
        tokenWidget.update(tokensUsed, maxTokens)
    }

    fun showResult(text: String) = runOnEdt {
        completedSteps.addAll(currentStep)
        currentStep.clear()
        stepList.repaint()
        outputArea.text = text
        cancelButton.isEnabled = false
    }

    fun reset() = runOnEdt {
        stepListModel.clear()
        completedSteps.clear()
        currentStep.clear()
        outputArea.text = ""
        tokenWidget.update(0, 0)
        cancelButton.isEnabled = false
    }

    /** Ensure UI mutations run on EDT regardless of calling thread. */
    private fun runOnEdt(action: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) {
            action()
        } else {
            SwingUtilities.invokeLater(action)
        }
    }

    private inner class StepListCellRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            val component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            val stepName = value as? String ?: return component
            icon = when {
                stepName in completedSteps -> AllIcons.RunConfigurations.TestPassed
                stepName in currentStep -> AllIcons.Process.Step_1
                else -> AllIcons.RunConfigurations.TestNotRan
            }
            if (!isSelected) {
                foreground = when {
                    stepName in completedSteps -> JBColor.foreground()
                    stepName in currentStep -> JBColor.BLUE
                    else -> JBColor.GRAY
                }
            }
            border = JBUI.Borders.empty(2, 4)
            return component
        }
    }
}

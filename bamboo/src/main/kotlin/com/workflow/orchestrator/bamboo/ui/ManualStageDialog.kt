package com.workflow.orchestrator.bamboo.ui

import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.core.model.bamboo.PlanVariableData
import com.workflow.orchestrator.core.services.BambooService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.JComponent
import javax.swing.JPanel

enum class TriggerMode {
    STAGE,
    FULL_BUILD
}

class ManualStageDialog(
    private val project: Project,
    private val planKey: String,
    private val stageName: String = "",
    private val scope: CoroutineScope,
    private val triggerMode: TriggerMode = TriggerMode.STAGE
) : DialogWrapper(project) {

    private val bambooService = project.getService(BambooService::class.java)
    private val variableEditors = mutableMapOf<String, JComponent>()
    private var variables: List<PlanVariableData> = emptyList()
    private var isLoading = true

    init {
        title = when (triggerMode) {
            TriggerMode.FULL_BUILD -> "Trigger Build"
            TriggerMode.STAGE -> "Run Stage: $stageName"
        }
        setOKButtonText(when (triggerMode) {
            TriggerMode.FULL_BUILD -> "Trigger"
            TriggerMode.STAGE -> "OK"
        })
        init()
        // Load variables asynchronously after dialog is shown
        scope.launch {
            val result = bambooService.getPlanVariables(planKey)
            if (!result.isError) {
                variables = result.data
            }
            isLoading = false
            invokeLater { rebuildForm() }
        }
    }

    private fun rebuildForm() {
        contentPanel?.removeAll()
        contentPanel?.add(createCenterPanel())
        contentPanel?.revalidate()
        contentPanel?.repaint()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(GridBagLayout())
        panel.border = JBUI.Borders.empty(8)

        val gbc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            anchor = GridBagConstraints.WEST
            insets = JBUI.insets(4)
        }

        if (isLoading) {
            gbc.gridy = 0
            gbc.gridx = 0
            gbc.gridwidth = 2
            val loadingPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
                add(JBLabel(AnimatedIcon.Default()))
                add(JBLabel("Loading variables..."))
            }
            panel.add(loadingPanel, gbc)
            return panel
        }

        variables.forEachIndexed { index, variable ->
            gbc.gridy = index

            // Label
            gbc.gridx = 0
            gbc.weightx = 0.0
            panel.add(JBLabel("${variable.name}:"), gbc)

            // Editor — checkbox for boolean-like values, text field otherwise
            gbc.gridx = 1
            gbc.weightx = 1.0
            val editor: JComponent = if (variable.value in listOf("true", "false")) {
                JBCheckBox().apply {
                    isSelected = variable.value == "true"
                }
            } else {
                JBTextField(variable.value, 20)
            }
            variableEditors[variable.name] = editor
            panel.add(editor, gbc)
        }

        if (variables.isEmpty()) {
            gbc.gridy = 0
            gbc.gridx = 0
            gbc.gridwidth = 2
            panel.add(JBLabel("No build variables configured for this plan."), gbc)
        }

        return panel
    }

    override fun doOKAction() {
        val vars = variableEditors.mapValues { (_, editor) ->
            when (editor) {
                is JBCheckBox -> editor.isSelected.toString()
                is JBTextField -> editor.text
                else -> ""
            }
        }

        scope.launch {
            when (triggerMode) {
                TriggerMode.FULL_BUILD -> bambooService.triggerBuild(planKey, vars)
                TriggerMode.STAGE -> bambooService.triggerStage(planKey, vars, stageName)
            }
        }

        super.doOKAction()
    }
}

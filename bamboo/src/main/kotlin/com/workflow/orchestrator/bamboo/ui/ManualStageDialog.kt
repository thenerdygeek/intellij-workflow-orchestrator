package com.workflow.orchestrator.bamboo.ui

import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.bamboo.api.BambooApiClient
import com.workflow.orchestrator.bamboo.api.dto.BambooPlanVariableDto
import com.workflow.orchestrator.core.model.ApiResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.JComponent
import javax.swing.JPanel

class ManualStageDialog(
    private val project: Project,
    private val apiClient: BambooApiClient,
    private val planKey: String,
    private val stageName: String,
    private val scope: CoroutineScope
) : DialogWrapper(project) {

    private val variableEditors = mutableMapOf<String, JComponent>()
    private var variables: List<BambooPlanVariableDto> = emptyList()

    init {
        title = "Run Stage: $stageName"
        init()
        // Load variables asynchronously after dialog is shown
        scope.launch {
            val result = apiClient.getVariables(planKey)
            if (result is ApiResult.Success) {
                variables = result.data
                invokeLater { rebuildForm() }
            }
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
            apiClient.triggerBuild(planKey, vars, stageName)
        }

        super.doOKAction()
    }
}

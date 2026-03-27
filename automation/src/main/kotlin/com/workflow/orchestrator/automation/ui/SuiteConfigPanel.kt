package com.workflow.orchestrator.automation.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.core.ui.StatusColors
import java.awt.BorderLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.*

class SuiteConfigPanel(
    private val project: Project
) : JPanel(BorderLayout()), Disposable {

    private val variablesPanel = JPanel(GridBagLayout())
    private val variableRows = mutableListOf<VariableRow>()

    data class VariableRow(
        val keyCombo: JComboBox<String>,
        val valueField: JBTextField
    )

    init {
        border = JBUI.Borders.emptyTop(8)

        // Stitch: uppercase section headers
        val headerLabel = JBLabel("VARIABLES & STAGES").apply {
            font = font.deriveFont(Font.BOLD, JBUI.scale(11).toFloat())
            foreground = StatusColors.SECONDARY_TEXT
        }

        val contentPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)

            add(JBLabel("VARIABLES").apply {
                font = font.deriveFont(Font.BOLD, JBUI.scale(10).toFloat())
                foreground = StatusColors.SECONDARY_TEXT
                border = JBUI.Borders.emptyBottom(4)
            })
            add(variablesPanel)
        }

        add(headerLabel, BorderLayout.NORTH)
        add(contentPanel, BorderLayout.CENTER)
    }

    fun setAvailableVariables(keys: List<String>) {
        variablesPanel.removeAll()
        variableRows.clear()

        for (key in keys) {
            addVariableRow(key, "")
        }
        variablesPanel.revalidate()
    }

    fun setVariableValues(vars: Map<String, String>) {
        for (row in variableRows) {
            val key = row.keyCombo.selectedItem as? String ?: continue
            val value = vars[key]
            if (value != null) {
                row.valueField.text = value
            }
        }
    }

    fun getVariables(): Map<String, String> {
        return variableRows.associate { row ->
            val key = row.keyCombo.selectedItem as? String ?: ""
            val value = row.valueField.text ?: ""
            key to value
        }.filter { it.key.isNotEmpty() && it.value.isNotEmpty() }
    }

    fun getEnabledStages(): List<String> = emptyList()

    private fun addVariableRow(key: String, value: String) {
        val keyCombo = JComboBox(arrayOf(key)).apply {
            isEditable = false
            preferredSize = JBUI.size(150, 28)
            // Stitch: monospace for variable keys
            font = Font(Font.MONOSPACED, Font.PLAIN, font.size)
        }
        val valueField = JBTextField(value).apply {
            preferredSize = JBUI.size(200, 28)
        }

        val gbc = GridBagConstraints().apply {
            gridy = variableRows.size
            fill = GridBagConstraints.HORIZONTAL
            insets = JBUI.insets(2)
        }

        gbc.gridx = 0; gbc.weightx = 0.3
        variablesPanel.add(keyCombo, gbc)

        gbc.gridx = 1; gbc.weightx = 0.7
        variablesPanel.add(valueField, gbc)

        variableRows.add(VariableRow(keyCombo, valueField))
    }

    override fun dispose() {}
}

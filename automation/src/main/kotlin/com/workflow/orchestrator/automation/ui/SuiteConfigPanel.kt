package com.workflow.orchestrator.automation.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.automation.service.AutomationSettingsService
import com.workflow.orchestrator.core.ui.StatusColors
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.*

/**
 * Suite variable selector with add/remove and persistence.
 * Available variables come from Bamboo plan variables. User picks which to include.
 * Selected variables and values persist per suite in AutomationSettingsService.
 */
class SuiteConfigPanel(
    @Suppress("unused") private val project: Project
) : JPanel(BorderLayout()), Disposable {

    private val log = Logger.getInstance(SuiteConfigPanel::class.java)
    private val automationSettings = AutomationSettingsService.getInstance()

    private val variablesPanel = JPanel(GridBagLayout())
    private val variableRows = mutableListOf<VariableRow>()
    private var availableKeys: List<String> = emptyList()
    private var currentSuitePlanKey: String = ""

    private data class VariableRow(
        val keyCombo: JComboBox<String>,
        val valueField: JBTextField,
        val removeButton: JButton
    )

    // Add Variable button — dropdown shows available keys not yet added
    private val addVariableCombo = JComboBox<String>().apply {
        preferredSize = JBUI.size(150, 28)
        font = Font(Font.MONOSPACED, Font.PLAIN, font.size)
    }
    private val addButton = JButton("+ Add").apply {
        isFocusPainted = false
        addActionListener { onAddVariable() }
    }

    init {
        border = JBUI.Borders.emptyTop(8)

        val headerLabel = JBLabel("VARIABLES").apply {
            font = font.deriveFont(Font.BOLD, JBUI.scale(11).toFloat())
            foreground = StatusColors.SECONDARY_TEXT
        }

        val addPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
            add(addVariableCombo)
            add(addButton)
        }

        val topPanel = JPanel(BorderLayout()).apply {
            add(headerLabel, BorderLayout.NORTH)
            add(addPanel, BorderLayout.SOUTH)
            border = JBUI.Borders.emptyBottom(4)
        }

        val scrollPane = JBScrollPane(variablesPanel).apply {
            border = null
            preferredSize = Dimension(0, 0) // let the scroll pane grow
        }

        add(topPanel, BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)
    }

    /**
     * Sets the available plan variable names from Bamboo.
     * These populate the "Add Variable" dropdown.
     */
    fun setAvailableVariables(keys: List<String>) {
        availableKeys = keys
        refreshAddCombo()
    }

    /**
     * Sets the default/plan-level values for variables.
     * Only applies to rows that already exist and have no user-set value.
     */
    fun setVariableValues(vars: Map<String, String>) {
        for (row in variableRows) {
            val key = row.keyCombo.selectedItem as? String ?: continue
            if (row.valueField.text.isBlank()) {
                val value = vars[key]
                if (value != null) {
                    row.valueField.text = value
                }
            }
        }
    }

    /**
     * Loads saved variables for a suite and sets them up in the UI.
     * Called by AutomationPanel when a suite is selected.
     */
    fun loadSuiteVariables(suitePlanKey: String) {
        currentSuitePlanKey = suitePlanKey
        variablesPanel.removeAll()
        variableRows.clear()

        val suiteConfig = automationSettings.getSuiteConfig(suitePlanKey)
        val savedVars = suiteConfig?.variables ?: mutableMapOf()

        log.info("[Automation:Config] Loading ${savedVars.size} saved variables for suite $suitePlanKey")
        for ((key, value) in savedVars) {
            addVariableRow(key, value)
        }

        refreshAddCombo()
        variablesPanel.revalidate()
        variablesPanel.repaint()
    }

    fun getVariables(): Map<String, String> {
        return variableRows.associate { row ->
            val key = row.keyCombo.selectedItem as? String ?: ""
            val value = row.valueField.text ?: ""
            key to value
        }.filter { it.key.isNotEmpty() && it.value.isNotEmpty() }
    }

    fun getEnabledStages(): List<String> = emptyList()

    private fun onAddVariable() {
        val key = addVariableCombo.selectedItem as? String
        if (key.isNullOrBlank()) return

        addVariableRow(key, "")
        refreshAddCombo()
        persistVariables()
        variablesPanel.revalidate()
        variablesPanel.repaint()
    }

    private fun onRemoveVariable(row: VariableRow) {
        variableRows.remove(row)
        rebuildVariablesPanel()
        refreshAddCombo()
        persistVariables()
    }

    private fun addVariableRow(key: String, value: String) {
        // Key dropdown — shows available keys, pre-selected to this key
        val allOptions = buildList {
            add(key)
            addAll(availableKeys.filter { it != key && it !in variableRows.map { r -> r.keyCombo.selectedItem } })
        }
        val keyCombo = JComboBox(allOptions.toTypedArray()).apply {
            selectedItem = key
            isEditable = false
            preferredSize = JBUI.size(140, 28)
            font = Font(Font.MONOSPACED, Font.PLAIN, font.size)
            addActionListener { persistVariables() }
        }
        val valueField = JBTextField(value).apply {
            preferredSize = JBUI.size(160, 28)
            addActionListener { persistVariables() } // persist on Enter
        }
        // Also persist on focus lost
        valueField.addFocusListener(object : java.awt.event.FocusAdapter() {
            override fun focusLost(e: java.awt.event.FocusEvent?) = persistVariables()
        })

        val removeButton = JButton("\u2717").apply {
            isFocusPainted = false
            preferredSize = JBUI.size(28, 28)
            toolTipText = "Remove variable"
        }

        val row = VariableRow(keyCombo, valueField, removeButton)
        removeButton.addActionListener { onRemoveVariable(row) }
        variableRows.add(row)

        val gbc = GridBagConstraints().apply {
            gridy = variableRows.size - 1
            fill = GridBagConstraints.HORIZONTAL
            insets = JBUI.insets(2)
        }

        gbc.gridx = 0; gbc.weightx = 0.3
        variablesPanel.add(keyCombo, gbc)

        gbc.gridx = 1; gbc.weightx = 0.6
        variablesPanel.add(valueField, gbc)

        gbc.gridx = 2; gbc.weightx = 0.0
        variablesPanel.add(removeButton, gbc)
    }

    private fun rebuildVariablesPanel() {
        variablesPanel.removeAll()
        val snapshot = variableRows.toList()
        variableRows.clear()
        for (row in snapshot) {
            val key = row.keyCombo.selectedItem as? String ?: ""
            val value = row.valueField.text ?: ""
            addVariableRow(key, value)
        }
        variablesPanel.revalidate()
        variablesPanel.repaint()
    }

    private fun refreshAddCombo() {
        val usedKeys = variableRows.mapNotNull { it.keyCombo.selectedItem as? String }.toSet()
        val remaining = availableKeys.filter { it !in usedKeys }

        addVariableCombo.removeAllItems()
        for (key in remaining) {
            addVariableCombo.addItem(key)
        }
        addButton.isEnabled = remaining.isNotEmpty()
    }

    /** Persist selected variables to AutomationSettingsService. */
    private fun persistVariables() {
        if (currentSuitePlanKey.isBlank()) return
        val vars = getVariables().toMutableMap()
        // Also include variables with empty values (to remember the key selection)
        for (row in variableRows) {
            val key = row.keyCombo.selectedItem as? String ?: continue
            if (key.isNotBlank() && key !in vars) {
                vars[key] = row.valueField.text ?: ""
            }
        }

        val config = automationSettings.getSuiteConfig(currentSuitePlanKey)
        if (config != null) {
            config.variables = vars.toMutableMap()
            config.lastModified = System.currentTimeMillis()
            automationSettings.saveSuiteConfig(config)
        }
        log.debug("[Automation:Config] Persisted ${vars.size} variables for suite $currentSuitePlanKey")
    }

    override fun dispose() {}
}

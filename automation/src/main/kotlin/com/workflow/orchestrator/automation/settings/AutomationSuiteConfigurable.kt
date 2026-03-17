package com.workflow.orchestrator.automation.settings

import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.automation.service.AutomationSettingsService
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.*

/**
 * Settings page for configuring automation suite plan keys.
 * Located under Tools > Workflow Orchestrator > Automation Suites.
 */
class AutomationSuiteConfigurable : SearchableConfigurable {

    private var mainPanel: JPanel? = null
    private val suiteRows = mutableListOf<SuiteRow>()
    private var suitesContainer: JPanel? = null

    data class SuiteRow(
        val displayNameField: JBTextField,
        val planKeyField: JBTextField,
        val panel: JPanel
    )

    override fun getId(): String = "workflow.orchestrator.automation.suites"
    override fun getDisplayName(): String = "Automation Suites"

    override fun createComponent(): JComponent {
        val settings = AutomationSettingsService.getInstance()

        mainPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8)
        }

        suitesContainer = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
        }

        // Load existing suites
        for (suite in settings.getAllSuites()) {
            addSuiteRow(suite.displayName, suite.planKey)
        }

        // If no suites, add an empty row
        if (suiteRows.isEmpty()) {
            addSuiteRow("", "")
        }

        val addButton = JButton("+ Add Suite").apply {
            addActionListener { addSuiteRow("", "") }
        }

        val headerLabel = JBLabel("Configure Bamboo automation suite plan keys.").apply {
            foreground = com.intellij.ui.JBColor(0x656D76, 0x8B949E)
            border = JBUI.Borders.emptyBottom(8)
        }

        mainPanel!!.add(headerLabel, BorderLayout.NORTH)
        mainPanel!!.add(JScrollPane(suitesContainer).apply {
            border = null
        }, BorderLayout.CENTER)
        mainPanel!!.add(JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(addButton)
        }, BorderLayout.SOUTH)

        return mainPanel!!
    }

    private fun addSuiteRow(displayName: String, planKey: String) {
        val rowPanel = JPanel(GridBagLayout()).apply {
            border = JBUI.Borders.emptyBottom(6)
            maximumSize = java.awt.Dimension(Int.MAX_VALUE, JBUI.scale(36))
        }

        val gbc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            insets = JBUI.insets(2)
        }

        val nameField = JBTextField(displayName).apply {
            emptyText.setText("Display name")
        }
        val keyField = JBTextField(planKey).apply {
            emptyText.setText("Bamboo plan key (e.g., PROJ-REGRESSION)")
        }
        val removeButton = JButton("✕").apply {
            isBorderPainted = false
            addActionListener {
                suitesContainer?.remove(rowPanel)
                suiteRows.removeIf { it.panel == rowPanel }
                suitesContainer?.revalidate()
                suitesContainer?.repaint()
            }
        }

        gbc.gridx = 0; gbc.weightx = 0.4
        rowPanel.add(nameField, gbc)
        gbc.gridx = 1; gbc.weightx = 0.5
        rowPanel.add(keyField, gbc)
        gbc.gridx = 2; gbc.weightx = 0.0
        rowPanel.add(removeButton, gbc)

        val row = SuiteRow(nameField, keyField, rowPanel)
        suiteRows.add(row)
        suitesContainer?.add(rowPanel)
        suitesContainer?.revalidate()
    }

    override fun isModified(): Boolean {
        val settings = AutomationSettingsService.getInstance()
        val current = settings.getAllSuites().map { it.planKey to it.displayName }.toSet()
        val edited = suiteRows
            .filter { it.planKeyField.text.isNotBlank() }
            .map { it.planKeyField.text to it.displayNameField.text }
            .toSet()
        return current != edited
    }

    override fun apply() {
        val settings = AutomationSettingsService.getInstance()
        // Clear and re-save
        settings.state.suites.clear()
        for (row in suiteRows) {
            val key = row.planKeyField.text.trim()
            val name = row.displayNameField.text.trim()
            if (key.isNotBlank()) {
                settings.saveSuiteConfig(
                    AutomationSettingsService.SuiteConfig(
                        planKey = key,
                        displayName = name.ifBlank { key },
                        lastModified = System.currentTimeMillis()
                    )
                )
            }
        }
    }

    override fun reset() {
        suiteRows.clear()
        suitesContainer?.removeAll()
        val settings = AutomationSettingsService.getInstance()
        for (suite in settings.getAllSuites()) {
            addSuiteRow(suite.displayName, suite.planKey)
        }
        if (suiteRows.isEmpty()) addSuiteRow("", "")
        suitesContainer?.revalidate()
    }
}

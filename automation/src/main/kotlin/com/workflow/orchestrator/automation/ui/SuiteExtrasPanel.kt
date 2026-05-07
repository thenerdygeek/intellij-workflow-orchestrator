package com.workflow.orchestrator.automation.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.automation.service.AutomationSettingsService
import com.workflow.orchestrator.core.ui.StatusColors
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel

/**
 * Per-suite "Custom Variables" sub-panel (PR 7 #7).
 *
 * A 2-column key/value editor for free-form variables that aren't part of the
 * Bamboo plan's `variableContext`. Persisted via
 * [AutomationSettingsService.setExtraVariables] (application-level, roams via
 * IntelliJ Settings Sync).
 *
 * Distinct from [SuiteConfigPanel] which is bound to the plan's known variable
 * keys (dropdown). This panel is for one-off overrides like `featureFlag=true`
 * that the user wants persisted with the suite but doesn't need to pick from a
 * Bamboo-defined list.
 *
 * **Save semantics.** Each row persists immediately on:
 *   - The X (remove) button click.
 *   - The "+ Add" button click.
 *   - The key field's focusLost event.
 *   - The value field's focusLost event.
 *   - Pressing Enter in either field.
 *
 * No debounce — IntelliJ's `PersistentStateComponent` writes the XML on its
 * own schedule (the `saveSettings` call from background flush), so the
 * write-amplification cost is negligible.
 */
class SuiteExtrasPanel : JPanel(BorderLayout()), Disposable {

    private val log = Logger.getInstance(SuiteExtrasPanel::class.java)
    private val automationSettings = AutomationSettingsService.getInstance()

    private val rowsPanel = JPanel(GridBagLayout())
    private val rows = mutableListOf<Row>()
    private var currentSuitePlanKey: String = ""

    private data class Row(
        val keyField: JBTextField,
        val valueField: JBTextField,
        val removeButton: JButton
    )

    private val addButton = JButton("+ Add Custom Variable").apply {
        isFocusPainted = false
        addActionListener { onAddRow() }
    }

    init {
        border = JBUI.Borders.emptyTop(8)

        val header = JBLabel("CUSTOM VARIABLES").apply {
            font = font.deriveFont(Font.BOLD, JBUI.scale(11).toFloat())
            foreground = StatusColors.SECONDARY_TEXT
            toolTipText = "Free-form variables persisted per suite. Merged into the trigger payload alongside Bamboo plan variables."
        }
        val headerWrapper = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
            add(header)
            add(addButton)
            border = JBUI.Borders.emptyBottom(4)
        }

        // Box layout so rowsPanel can grow without stretching the header.
        val center = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(headerWrapper)
            add(rowsPanel)
        }
        add(center, BorderLayout.NORTH)
    }

    /**
     * Loads any saved extras for [suitePlanKey] and rebuilds the table.
     * Called by [AutomationPanel] whenever the user picks a new suite.
     */
    fun loadSuite(suitePlanKey: String) {
        currentSuitePlanKey = suitePlanKey
        rows.clear()
        rowsPanel.removeAll()
        val saved = automationSettings.getExtraVariables(suitePlanKey)
        log.info("[Automation:Extras] Loading ${saved.size} extras for suite $suitePlanKey")
        for ((k, v) in saved) addRowInternal(k, v)
        rowsPanel.revalidate()
        rowsPanel.repaint()
    }

    /** Returns the current edited extras as a flat map, dropping blank keys. */
    fun getExtras(): Map<String, String> = rows
        .associate { (it.keyField.text ?: "") to (it.valueField.text ?: "") }
        .filter { it.key.isNotBlank() }

    private fun onAddRow() {
        addRowInternal("", "")
        rowsPanel.revalidate()
        rowsPanel.repaint()
        // No persist on add — empty rows aren't saved (filtered out by getExtras).
    }

    private fun addRowInternal(key: String, value: String) {
        val keyField = JBTextField(key).apply {
            preferredSize = JBUI.size(140, 28)
            font = Font(Font.MONOSPACED, Font.PLAIN, font.size)
            addActionListener { persist() }
            addFocusListener(object : java.awt.event.FocusAdapter() {
                override fun focusLost(e: java.awt.event.FocusEvent?) = persist()
            })
        }
        val valueField = JBTextField(value).apply {
            preferredSize = JBUI.size(180, 28)
            addActionListener { persist() }
            addFocusListener(object : java.awt.event.FocusAdapter() {
                override fun focusLost(e: java.awt.event.FocusEvent?) = persist()
            })
        }
        val removeButton = JButton("✗").apply {
            isFocusPainted = false
            preferredSize = JBUI.size(28, 28)
            toolTipText = "Remove this variable"
        }
        val row = Row(keyField, valueField, removeButton)
        removeButton.addActionListener { onRemoveRow(row) }
        rows.add(row)

        val gbc = GridBagConstraints().apply {
            gridy = rows.size - 1
            fill = GridBagConstraints.HORIZONTAL
            insets = JBUI.insets(2)
        }
        gbc.gridx = 0; gbc.weightx = 0.3
        rowsPanel.add(keyField, gbc)
        gbc.gridx = 1; gbc.weightx = 0.6
        rowsPanel.add(valueField, gbc)
        gbc.gridx = 2; gbc.weightx = 0.0
        rowsPanel.add(removeButton, gbc)
    }

    private fun onRemoveRow(row: Row) {
        rows.remove(row)
        rowsPanel.removeAll()
        val snapshot = rows.toList()
        rows.clear()
        for (r in snapshot) addRowInternal(r.keyField.text ?: "", r.valueField.text ?: "")
        rowsPanel.revalidate()
        rowsPanel.repaint()
        persist()
    }

    private fun persist() {
        if (currentSuitePlanKey.isBlank()) return
        val extras = getExtras()
        automationSettings.setExtraVariables(currentSuitePlanKey, extras)
        log.debug("[Automation:Extras] Persisted ${extras.size} extras for suite $currentSuitePlanKey")
    }

    override fun dispose() {}
}

package com.workflow.orchestrator.automation.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.automation.service.AutomationSettingsService
import com.workflow.orchestrator.core.model.bamboo.PlanVariableData
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.ui.ComboBoxWidth
import com.workflow.orchestrator.core.ui.StatusColors
import com.workflow.orchestrator.core.ui.bindBoundedWidth
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.*

/**
 * Suite variable override panel — replaces the old two-step "pick from dropdown then Add"
 * flow with a single "Override a variable" button. Each row carries:
 *   - A categorised key dropdown (PLAN → PARENT → GLOBAL, alpha within each category).
 *   - A value field pre-filled with the plan's current default for the selected key.
 *     On dropdown change the field is always reset to the new key's default; if the user
 *     has edited the field and then changes the key, the new default takes precedence —
 *     this is intentional: key changes after editing are rare and re-fill is cleaner than
 *     guessing whether the old content was deliberate.
 *   - An X button to remove the row.
 *
 * The Docker tags variable (configured via [PluginSettings.State.bambooBuildVariableName],
 * defaulting to "DockerTagsAsJSON") is excluded from all key dropdowns — it is added
 * downstream by QueueService as part of the trigger payload.
 *
 * Persistence uses [AutomationSettingsService] / [AutomationSettingsService.SuiteConfig.variables].
 * The schema is unchanged.
 */
class SuiteConfigPanel(
    private val project: Project
) : JPanel(BorderLayout()), Disposable {

    private val log = Logger.getInstance(SuiteConfigPanel::class.java)
    private val automationSettings = AutomationSettingsService.getInstance()

    /** All plan variables received from Bamboo, sorted for display. */
    private var availableVariables: List<PlanVariableData> = emptyList()
    private var currentSuitePlanKey: String = ""

    /** Live rows. Each row holds one variable override entry. */
    private val variableRows = mutableListOf<VariableRow>()

    private val variablesPanel = JPanel(GridBagLayout())

    /** Shown only when there are zero rows and no overrides have been added yet. */
    private val emptyHint = JBLabel("Click '+ Override a variable' to override a plan default.").apply {
        foreground = StatusColors.SECONDARY_TEXT
        font = font.deriveFont(Font.PLAIN, JBUI.scale(11).toFloat())
        border = JBUI.Borders.empty(4, 2)
    }

    private val overrideButton = JButton("+ Override a variable").apply {
        isFocusPainted = false
        addActionListener { onAddRow() }
    }

    /** Resolves the effective Docker tags variable name from settings. */
    private val dockerTagsVariableName: String
        get() = PluginSettings.getInstance(project).state.bambooBuildVariableName
            ?.takeIf { it.isNotBlank() } ?: "DockerTagsAsJSON"

    private data class VariableRow(
        val keyCombo: JComboBox<VariableOption>,
        val valueField: JBTextField,
        val removeButton: JButton
    )

    /**
     * A single entry in a key dropdown.
     * [variableType] is used for category-prefix rendering; [defaultValue] pre-fills
     * the value field when this option is selected.
     */
    private data class VariableOption(
        val name: String,
        val variableType: String,
        val defaultValue: String
    ) {
        override fun toString(): String = name // fallback; renderer overrides this
    }

    init {
        border = JBUI.Borders.emptyTop(8)

        val headerLabel = JBLabel("VARIABLES").apply {
            font = font.deriveFont(Font.BOLD, JBUI.scale(11).toFloat())
            foreground = StatusColors.SECONDARY_TEXT
        }

        val topPanel = JPanel(BorderLayout()).apply {
            add(headerLabel, BorderLayout.NORTH)
            add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
                add(overrideButton)
            }, BorderLayout.SOUTH)
            border = JBUI.Borders.emptyBottom(4)
        }

        val scrollPane = JBScrollPane(variablesPanel).apply {
            border = null
            preferredSize = Dimension(0, 0)
        }

        add(topPanel, BorderLayout.NORTH)
        add(emptyHint, BorderLayout.CENTER)

        // variablesPanel is shown inside a scroll pane once rows exist
        add(scrollPane, BorderLayout.SOUTH)
    }

    // ──────────────────────────── Public API ────────────────────────────

    /**
     * Sets the full list of plan variables from Bamboo. Used to populate the key
     * dropdown on each row and to pre-fill value fields with plan defaults.
     * Filters out the Docker tags variable ([dockerTagsVariableName]) automatically.
     * Sorted PLAN → PARENT → GLOBAL, then alpha within each category.
     * Also reloads any existing rows so their dropdowns reflect the fresh variable list.
     *
     * Replaces the old two-parameter (varKeys + setVariableValues) pattern.
     */
    fun setAvailableVariables(vars: List<PlanVariableData>) {
        availableVariables = sortedVariables(vars)
        // Refresh existing rows so their combos stay coherent after a suite swap.
        rebuildVariablesPanel()
    }

    /**
     * Loads saved variable overrides for [suitePlanKey] and rebuilds the panel.
     * Called by AutomationPanel whenever the user selects a suite.
     */
    fun loadSuiteVariables(suitePlanKey: String) {
        currentSuitePlanKey = suitePlanKey
        variablesPanel.removeAll()
        variableRows.clear()

        val savedVars = automationSettings.getSuiteConfig(suitePlanKey)?.variables ?: mutableMapOf()
        log.info("[Automation:Config] Loading ${savedVars.size} saved variable overrides for suite $suitePlanKey")
        for ((key, value) in savedVars) {
            addVariableRow(key, value)
        }

        syncEmptyHint()
        variablesPanel.revalidate()
        variablesPanel.repaint()
    }

    /** Returns the current variable overrides as a flat map, dropping blank keys. */
    fun getVariables(): Map<String, String> =
        variableRows.associate { row ->
            val key = (row.keyCombo.selectedItem as? VariableOption)?.name ?: ""
            key to (row.valueField.text ?: "")
        }.filter { it.key.isNotEmpty() }

    // Kept for interface compatibility — no longer sets values externally; no-op.
    fun getEnabledStages(): List<String> = emptyList()

    // ──────────────────────────── Internal helpers ────────────────────────────

    private fun onAddRow() {
        addVariableRow("", "")
        syncEmptyHint()
        variablesPanel.revalidate()
        variablesPanel.repaint()
    }

    private fun addVariableRow(savedKey: String, savedValue: String) {
        val options = buildOptions(excludeKeys = currentlySelectedKeys())

        // If the savedKey is not in options (e.g. variable was removed from plan),
        // include it as an ad-hoc PLAN-type entry so the user can see and remove it.
        val allOptions: List<VariableOption> = if (savedKey.isNotBlank() && options.none { it.name == savedKey }) {
            val phantom = VariableOption(savedKey, "PLAN", savedValue)
            listOf(phantom) + options
        } else {
            options
        }

        val keyCombo = JComboBox(allOptions.toTypedArray()).apply {
            isEditable = false
            font = Font(Font.MONOSPACED, Font.PLAIN, font.size)
            bindBoundedWidth(ComboBoxWidth.SHORT)
            renderer = CategoryPrefixRenderer()
        }

        // Pre-select the saved key if present.
        val initial = allOptions.firstOrNull { it.name == savedKey }
        if (initial != null) keyCombo.selectedItem = initial

        val valueField = JBTextField(savedValue).apply {
            preferredSize = JBUI.size(160, 28)
            addActionListener { persistVariables() }
        }
        valueField.addFocusListener(object : java.awt.event.FocusAdapter() {
            override fun focusLost(e: java.awt.event.FocusEvent?) = persistVariables()
        })

        // On key selection change: always re-fill value field with the new key's plan default.
        // (See class KDoc for the design decision on this behaviour.)
        keyCombo.addActionListener {
            val selected = keyCombo.selectedItem as? VariableOption ?: return@addActionListener
            valueField.text = selected.defaultValue
            // Refresh other rows so freed/taken keys are reflected.
            refreshAllCombos()
            persistVariables()
        }

        val removeButton = JButton("✗").apply {
            isFocusPainted = false
            preferredSize = JBUI.size(28, 28)
            toolTipText = "Remove variable override"
        }

        val row = VariableRow(keyCombo, valueField, removeButton)
        removeButton.addActionListener { onRemoveRow(row) }
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

    private fun onRemoveRow(row: VariableRow) {
        variableRows.remove(row)
        rebuildVariablesPanel()
        syncEmptyHint()
        persistVariables()
    }

    /**
     * Rebuilds the variablesPanel from scratch using the current [variableRows] state.
     * Called after removes and after [setAvailableVariables] to keep dropdowns in sync.
     */
    private fun rebuildVariablesPanel() {
        variablesPanel.removeAll()
        val snapshot = variableRows.map {
            val key = (it.keyCombo.selectedItem as? VariableOption)?.name ?: ""
            val value = it.valueField.text ?: ""
            key to value
        }
        variableRows.clear()
        for ((key, value) in snapshot) {
            addVariableRow(key, value)
        }
        variablesPanel.revalidate()
        variablesPanel.repaint()
    }

    /**
     * Refreshes just the combo item-lists for all existing rows without rebuilding
     * the panel. Used on inline key changes to update which keys are available in
     * sibling rows.
     */
    private fun refreshAllCombos() {
        for ((idx, row) in variableRows.withIndex()) {
            val currentKey = (row.keyCombo.selectedItem as? VariableOption)?.name ?: ""
            val keysUsedByOthers = variableRows
                .filterIndexed { i, _ -> i != idx }
                .mapNotNull { (it.keyCombo.selectedItem as? VariableOption)?.name }
                .toSet()
            val options = buildOptions(excludeKeys = keysUsedByOthers)
            val currentOption = options.firstOrNull { it.name == currentKey }

            // Temporarily suppress the keyCombo action listener to avoid cascade.
            val listeners = row.keyCombo.actionListeners.toList()
            listeners.forEach { row.keyCombo.removeActionListener(it) }
            row.keyCombo.removeAllItems()
            for (opt in options) row.keyCombo.addItem(opt)
            if (currentOption != null) row.keyCombo.selectedItem = currentOption
            listeners.forEach { row.keyCombo.addActionListener(it) }
        }
    }

    /**
     * Returns a sorted [VariableOption] list from [availableVariables], excluding:
     *   - The Docker tags variable (case-insensitive match against [dockerTagsVariableName]).
     *   - Any keys already chosen in [excludeKeys].
     */
    private fun buildOptions(excludeKeys: Set<String>): List<VariableOption> {
        val dockerKey = dockerTagsVariableName
        return availableVariables
            .filter { v ->
                !v.name.equals(dockerKey, ignoreCase = true) && v.name !in excludeKeys
            }
            .map { v -> VariableOption(v.name, v.variableType, v.value) }
    }

    /** Returns the set of keys currently selected across all rows. */
    private fun currentlySelectedKeys(): Set<String> =
        variableRows.mapNotNull { (it.keyCombo.selectedItem as? VariableOption)?.name }.toSet()

    /**
     * Sorts plan variables PLAN → PARENT → GLOBAL, alpha within each category.
     * Unknown variableType values sort to the end.
     */
    private fun sortedVariables(vars: List<PlanVariableData>): List<PlanVariableData> {
        val categoryRank = mapOf("PLAN" to 0, "PARENT" to 1, "GLOBAL" to 2)
        return vars.sortedWith(
            compareBy({ categoryRank[it.variableType.uppercase()] ?: 99 }, { it.name.lowercase() })
        )
    }

    /** Shows the empty-state hint when there are no rows; hides it otherwise. */
    private fun syncEmptyHint() {
        val hasRows = variableRows.isNotEmpty()
        emptyHint.isVisible = !hasRows
    }

    /** Persists the current variable overrides to [AutomationSettingsService]. */
    private fun persistVariables() {
        if (currentSuitePlanKey.isBlank()) return
        val vars = buildMap<String, String> {
            for (row in variableRows) {
                val key = (row.keyCombo.selectedItem as? VariableOption)?.name ?: continue
                if (key.isNotBlank()) put(key, row.valueField.text ?: "")
            }
        }.toMutableMap()

        // A-P1-8: when getSuiteConfig returns null, create a fresh SuiteConfig rather
        // than silently discarding the user's edits.
        val existing = automationSettings.getSuiteConfig(currentSuitePlanKey)
        val config = existing ?: AutomationSettingsService.SuiteConfig(
            planKey = currentSuitePlanKey,
            displayName = currentSuitePlanKey
        ).also {
            log.warn(
                "[Automation:Config] No SuiteConfig for '$currentSuitePlanKey' on persist; " +
                "creating one to preserve user-edited variable overrides (A-P1-8)"
            )
        }
        config.variables = vars
        config.lastModified = System.currentTimeMillis()
        automationSettings.saveSuiteConfig(config)
        log.debug("[Automation:Config] Persisted ${vars.size} variable overrides for suite $currentSuitePlanKey")
    }

    override fun dispose() {}

    // ──────────────────────────── Cell renderer ────────────────────────────

    /**
     * Renders each [VariableOption] in the dropdown with a small category prefix
     * in secondary-text colour, e.g.:
     *
     *   PLAN  | DEPLOY_ENV
     *   PARENT| TIMEOUT_MS
     */
    private inner class CategoryPrefixRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            val label = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus) as JLabel
            if (value is VariableOption) {
                val category = value.variableType.uppercase().padEnd(6)
                label.text = "$category| ${value.name}"
                label.font = Font(Font.MONOSPACED, Font.PLAIN, label.font.size)
            }
            return label
        }
    }
}

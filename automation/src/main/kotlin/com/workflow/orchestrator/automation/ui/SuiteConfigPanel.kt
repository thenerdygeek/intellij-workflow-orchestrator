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
import java.awt.CardLayout
import java.awt.Component
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

    /**
     * CardLayout swaps between the empty-state hint and the variables scrollPane in
     * the panel's CENTER region. v0.85.0-alpha shipped with the scrollPane in
     * BorderLayout.SOUTH plus the no-op `preferredSize = Dimension(0, 0)` carried
     * over from when the scrollPane lived in CENTER — SOUTH respects that preferred
     * height, collapsing the rows region to zero pixels. Clicking "+ Override a
     * variable" added rows that were rendered invisible. Mirrors the
     * empty-vs-content pattern used by [TagStagingPanel].
     */
    private val cardLayout = CardLayout()
    private val cardPanel = JPanel(cardLayout)

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
     *
     * - Real entries: [name], [variableType] (PLAN/PARENT/GLOBAL), [defaultValue].
     * - Separator entries: [isSeparator] = true. Used as a visual bar between
     *   variable categories with [categoryCaption] shown as a small uppercase
     *   label on the bar (e.g. "PROJECT VARIABLES"). Non-selectable — see
     *   [SkipSeparatorComboBox.setSelectedIndex]. Real-entry fields default to
     *   empty for separators.
     */
    internal data class VariableOption(
        val name: String,
        val variableType: String,
        val defaultValue: String,
        val isSeparator: Boolean = false,
        val categoryCaption: String = ""
    ) {
        override fun toString(): String = if (isSeparator) categoryCaption else name
    }

    /**
     * JComboBox that refuses to select a separator entry. If the caller (popup
     * click, arrow-key navigation, or `setSelectedItem`) targets a separator,
     * we forward to the nearest real entry — next, then previous — so the
     * combo's selected value contract (always a real `VariableOption`) holds.
     */
    internal class SkipSeparatorComboBox(items: Array<VariableOption>) : JComboBox<VariableOption>(items) {
        override fun setSelectedIndex(anIndex: Int) {
            if (anIndex < 0 || anIndex >= itemCount) {
                super.setSelectedIndex(anIndex)
                return
            }
            val item = getItemAt(anIndex)
            if (item == null || !item.isSeparator) {
                super.setSelectedIndex(anIndex)
                return
            }
            val nextReal = ((anIndex + 1) until itemCount).firstOrNull { !getItemAt(it).isSeparator }
                ?: ((anIndex - 1) downTo 0).firstOrNull { !getItemAt(it).isSeparator }
            if (nextReal != null) super.setSelectedIndex(nextReal)
            // else: only separators present (impossible by construction) — leave selection alone.
        }
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

        val scrollPane = JBScrollPane(variablesPanel).apply { border = null }
        cardPanel.add(emptyHint, EMPTY_CARD)
        cardPanel.add(scrollPane, ROWS_CARD)
        cardLayout.show(cardPanel, EMPTY_CARD)

        add(topPanel, BorderLayout.NORTH)
        add(cardPanel, BorderLayout.CENTER)
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

    // ──────────────────────────── Internal helpers ────────────────────────────

    private fun onAddRow() {
        addVariableRow("", "")
        syncEmptyHint()
        variablesPanel.revalidate()
        variablesPanel.repaint()
    }

    private fun addVariableRow(savedKey: String, savedValue: String) {
        val realOptions = buildOptions(excludeKeys = currentlySelectedKeys())

        // If the savedKey is not in realOptions (e.g. variable was removed from plan),
        // include it as an ad-hoc PLAN-type entry so the user can see and remove it.
        val withPhantom: List<VariableOption> = if (savedKey.isNotBlank() && realOptions.none { it.name == savedKey }) {
            val phantom = VariableOption(savedKey, "PLAN", savedValue)
            listOf(phantom) + realOptions
        } else {
            realOptions
        }
        val allOptions = interleaveSeparators(withPhantom)

        val keyCombo = SkipSeparatorComboBox(allOptions.toTypedArray()).apply {
            isEditable = false
            font = Font(Font.MONOSPACED, Font.PLAIN, font.size)
            bindBoundedWidth(ComboBoxWidth.SHORT)
            renderer = CategoryGroupedRenderer()
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
            val realOptions = buildOptions(excludeKeys = keysUsedByOthers)
            val options = interleaveSeparators(realOptions)
            val currentOption = options.firstOrNull { !it.isSeparator && it.name == currentKey }

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
     * Returns [realOptions] with non-clickable separator entries inserted between
     * variableType groups so the dropdown visually reads as `PLAN VARIABLES` block,
     * `PROJECT VARIABLES` block, `GLOBAL VARIABLES` block. The first group gets a
     * leading separator too, so every group is uniformly captioned.
     *
     * `realOptions` must already be sorted PLAN → PARENT → GLOBAL by [sortedVariables].
     */
    private fun interleaveSeparators(realOptions: List<VariableOption>): List<VariableOption> {
        if (realOptions.isEmpty()) return emptyList()
        val out = mutableListOf<VariableOption>()
        var lastCategory: String? = null
        for (opt in realOptions) {
            val cat = opt.variableType.uppercase()
            if (cat != lastCategory) {
                out += VariableOption(
                    name = "",
                    variableType = cat,
                    defaultValue = "",
                    isSeparator = true,
                    categoryCaption = captionFor(cat)
                )
                lastCategory = cat
            }
            out += opt
        }
        return out
    }

    /** Maps Bamboo's variableType enum to a user-facing caption for the separator bar. */
    private fun captionFor(category: String): String = when (category) {
        "PLAN" -> "Plan variables"
        "PARENT" -> "Project variables"
        "GLOBAL" -> "Global variables"
        else -> category.lowercase().replaceFirstChar { it.uppercase() } + " variables"
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
                !isDockerTagsVariable(v.name, dockerKey) && v.name !in excludeKeys
            }
            .map { v -> VariableOption(v.name, v.variableType, v.value) }
    }

    companion object {
        private const val EMPTY_CARD = "empty"
        private const val ROWS_CARD = "rows"

        /**
         * Returns `true` when [name] refers to the Docker tags variable.
         *
         * The check is case-insensitive so that user-configured names like
         * "DockerTagsAsJSON", "dockertagsasjson", or "DOCKERTAGSASJSON" all
         * match consistently.
         *
         * Extracted here (instead of inlined in [buildOptions]) so that
         * [com.workflow.orchestrator.automation.ui.SuiteConfigPanelSortTest]
         * can exercise the filter logic without instantiating the panel or
         * requiring IntelliJ infrastructure.
         */
        internal fun isDockerTagsVariable(name: String, dockerTagsVariableName: String): Boolean =
            name.equals(dockerTagsVariableName, ignoreCase = true)
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

    /** Switches the CardLayout between the empty hint and the rows scrollPane. */
    private fun syncEmptyHint() {
        cardLayout.show(cardPanel, if (variableRows.isEmpty()) EMPTY_CARD else ROWS_CARD)
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
     * Renders dropdown items in two modes:
     *
     * - **Real entry**: plain monospace name. Visual grouping comes from the
     *   separator above, so the per-item `PLAN |` prefix the prior renderer
     *   added is no longer needed.
     * - **Separator entry**: a thin horizontal panel (`SeparatorRow`) with the
     *   category caption in small uppercase secondary-text colour and a 1px
     *   matte line below — visually reads as a non-clickable section header.
     *   `SkipSeparatorComboBox.setSelectedIndex` enforces non-selectability.
     *
     * The separator panel ignores selection highlighting (returns the same
     * background even when `isSelected` is true) so hovering over it doesn't
     * produce a misleading clickable look.
     */
    private inner class CategoryGroupedRenderer : ListCellRenderer<VariableOption> {
        private val itemLabel = JBLabel().apply {
            font = Font(Font.MONOSPACED, Font.PLAIN, JBUI.scale(12))
            isOpaque = true
            border = JBUI.Borders.empty(2, 8)
        }
        private val separatorRow = SeparatorRow()

        override fun getListCellRendererComponent(
            list: JList<out VariableOption>?,
            value: VariableOption?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            if (value == null) {
                itemLabel.text = ""
                return itemLabel
            }
            if (value.isSeparator) {
                separatorRow.setCaption(value.categoryCaption)
                return separatorRow
            }
            itemLabel.text = value.name
            itemLabel.background = if (isSelected) list?.selectionBackground ?: UIManager.getColor("List.background")
                                    else UIManager.getColor("List.background")
            itemLabel.foreground = if (isSelected) list?.selectionForeground ?: UIManager.getColor("List.foreground")
                                    else UIManager.getColor("List.foreground")
            return itemLabel
        }
    }

    /**
     * The non-clickable separator row inside the dropdown popup: small uppercase
     * caption above a 1px horizontal rule. Always renders as the list's
     * background regardless of hover/selection state — the JComboBox subclass
     * already prevents selecting a separator, but we also avoid the visual
     * affordance of a selectable item.
     */
    private class SeparatorRow : JPanel(BorderLayout()) {
        private val caption = JBLabel().apply {
            font = font.deriveFont(Font.BOLD, JBUI.scale(10).toFloat())
            foreground = StatusColors.SECONDARY_TEXT
            border = JBUI.Borders.empty(4, 8, 1, 8)
        }

        init {
            isOpaque = true
            background = UIManager.getColor("List.background")
            add(caption, BorderLayout.NORTH)
            add(JSeparator(SwingConstants.HORIZONTAL).apply {
                foreground = UIManager.getColor("Separator.foreground")
                    ?: UIManager.getColor("Component.borderColor")
                    ?: java.awt.Color.GRAY
            }, BorderLayout.SOUTH)
        }

        fun setCaption(text: String) {
            caption.text = text.uppercase()
        }
    }
}

package com.workflow.orchestrator.bamboo.ui

import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.invokeLater as platformInvokeLater
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import com.workflow.orchestrator.core.model.bamboo.PlanVariableData
import com.workflow.orchestrator.core.services.BambooService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import com.intellij.ui.table.JBTable
import javax.swing.JTable
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

/**
 * Trigger mode for [ManualStageDialog].
 *
 * - [STAGE] — trigger a single named manual stage (original dialog behaviour).
 * - [CUSTOM_STAGES] — show a checkbox list of all plan stages, let the user pick one
 *   or more. Pre-checks: saved default if any; else first stage; else nothing.
 *   OK button disabled when nothing is selected or stage load failed.
 *   On confirm, the selected stage names + optional "save as default" flag are returned
 *   via [ManualStageDialog.getResult].
 */
enum class TriggerMode {
    STAGE,
    CUSTOM_STAGES
}

/**
 * Result returned by [ManualStageDialog.getResult] in [TriggerMode.CUSTOM_STAGES] mode.
 *
 * @param selectedStages the set of stage names the user checked.
 * @param saveAsDefault true if the "Save as default for this suite" checkbox was checked.
 */
data class ManualStageDialogResult(
    val selectedStages: Set<String>,
    val saveAsDefault: Boolean
)

/**
 * Dialog for triggering a Bamboo build stage or full build, with optional
 * variable overrides and (in [TriggerMode.CUSTOM_STAGES] mode) stage selection.
 *
 * In CUSTOM_STAGES mode, the caller reads [getResult] after `showAndGet()` returns.
 * The dialog itself does NOT call [BambooService.triggerBuild] in CUSTOM_STAGES mode
 * — it only collects the selection + the "Save as default" preference.
 *
 * @param savedDefaultStages when non-null, used to pre-check the matching stage
 *   checkboxes. When null, falls back to pre-checking the first stage.
 * @param variablesPreview when non-null and in CUSTOM_STAGES mode, renders a
 *   read-only summary panel showing the variables that will be sent with the
 *   trigger. The Automation tab passes its merged var map (suiteConfigPanel
 *   variable overrides + dockerTagsAsJson) so the user can verify the payload
 *   before confirming. Variables remain editable on the Automation tab itself;
 *   this preview is intentionally read-only to avoid two competing edit surfaces.
 * @param suiteDisplayName when non-null and in CUSTOM_STAGES mode, renders a
 *   non-editable "Suite: <displayName>" header at the top of the dialog so
 *   the user can confirm which suite is about to fire without relying on the
 *   title bar alone. Pass null (the default) for STAGE/FULL_BUILD callers or
 *   whenever the display name is not available.
 *
 *   **AutomationPanel call-site note:** the AutomationPanel's `onTriggerCustomized()`
 *   should pass `suiteDisplayName = AutomationSettingsService.getInstance()
 *   .getSuiteConfig(planKey)?.displayName` to wire this header. That call site
 *   lives in `:automation` — update it once the sibling agent's changes land.
 */
class ManualStageDialog(
    private val project: Project,
    private val planKey: String,
    private val stageName: String = "",
    private val scope: CoroutineScope,
    private val triggerMode: TriggerMode = TriggerMode.STAGE,
    private val savedDefaultStages: Set<String>? = null,
    private val variablesPreview: Map<String, String>? = null,
    private val suiteDisplayName: String? = null
) : DialogWrapper(project) {

    companion object {
        /** Debounce delay (ms) for the table row-height recompute on resize. */
        private const val RESIZE_DEBOUNCE_MS = 200
    }

    private val log = Logger.getInstance(ManualStageDialog::class.java)
    private val bambooService = project.getService(BambooService::class.java)
    private val variableEditors = mutableMapOf<String, JComponent>()
    private var variables: List<PlanVariableData> = emptyList()
    private var isLoading = true

    // CUSTOM_STAGES: one checkbox per plan stage; null until stages load.
    private var stageCheckboxes: List<Pair<String, JBCheckBox>>? = null
    // CUSTOM_STAGES: error message to show when stage load fails.
    private var stageLoadError: String? = null
    // Whether stage loading is in progress (CUSTOM_STAGES only).
    private var isLoadingStages = triggerMode == TriggerMode.CUSTOM_STAGES
    // CUSTOM_STAGES: "Save as default for this suite" checkbox.
    private var saveAsDefaultCheckbox: JBCheckBox? = null

    // Stable slot panels — built ONCE in createCenterPanel(), then refilled in
    // place after async load. Replacing the entire center panel via
    // contentPanel.removeAll/add (the prior pattern) was racy: BoxLayout's
    // preferred-size cache + DialogWrapper's internal myContentPanel layout
    // could leave the south button row briefly invisible after the swap.
    // Updating slots in place keeps the dialog frame and south panel untouched.
    private var stageSlot: JPanel? = null
    private var varsSlot: JPanel? = null

    init {
        title = when (triggerMode) {
            TriggerMode.STAGE -> "Run Stage: $stageName"
            TriggerMode.CUSTOM_STAGES -> "Trigger Customized Build"
        }
        setOKButtonText(when (triggerMode) {
            TriggerMode.STAGE -> "OK"
            TriggerMode.CUSTOM_STAGES -> "Trigger"
        })
        // OK disabled until at least one stage is checked (CUSTOM_STAGES only).
        if (triggerMode == TriggerMode.CUSTOM_STAGES) {
            okAction.isEnabled = false
        }
        init()

        // Load variables and (for CUSTOM_STAGES) plan stages asynchronously.
        // All Swing component construction and field writes happen on the EDT inside
        // invokeLater — no cross-thread JMM visibility gap, no off-EDT widget creation.
        scope.launch {
            val varResult = bambooService.getPlanVariables(planKey)
            val loadedVariables: List<PlanVariableData> = if (!varResult.isError) varResult.data!! else emptyList()

            // Compute plain data on IO (no Swing) for CUSTOM_STAGES.
            data class StagesData(
                val stageNames: List<String>?,   // null on error
                val errorMessage: String?
            )
            val stagesData: StagesData? = if (triggerMode == TriggerMode.CUSTOM_STAGES) {
                val stagesResult = bambooService.getLatestBuild(planKey)
                if (!stagesResult.isError && stagesResult.data != null) {
                    val names = stagesResult.data!!.stages.map { it.name }
                    if (names.isNotEmpty()) StagesData(stageNames = names, errorMessage = null)
                    else StagesData(
                        stageNames = null,
                        errorMessage = "No stage information available for plan $planKey. " +
                            "Trigger a full build first so Bamboo records the stage list."
                    )
                } else {
                    StagesData(
                        stageNames = null,
                        errorMessage = "Couldn't load stages from Bamboo: ${stagesResult.summary}. " +
                            "Refresh, or cancel."
                    )
                }
            } else null

            // All field writes and Swing construction happen on the EDT.
            invokeLater {
                variables = loadedVariables

                if (stagesData != null) {
                    isLoadingStages = false
                    if (stagesData.stageNames != null) {
                        stageCheckboxes = stagesData.stageNames.map { name ->
                            // Pre-check logic (H6): saved default if any; else first stage; else nothing.
                            val preChecked = when {
                                savedDefaultStages != null -> name in savedDefaultStages
                                else -> name == stagesData.stageNames.first()
                            }
                            val cb = JBCheckBox(name, preChecked)
                            cb.addActionListener { updateOkButton() }
                            name to cb
                        }
                    } else {
                        stageLoadError = stagesData.errorMessage
                    }
                }

                isLoading = false
                refillSlots()
                updateOkButton()
            }
        }
    }

    /**
     * Returns the set of stage names the user checked. Only meaningful in CUSTOM_STAGES mode.
     * @deprecated Use [getResult] which also returns the "save as default" flag.
     */
    fun getSelectedStages(): Set<String> = getResult().selectedStages

    /**
     * Returns the full dialog result after the user clicked OK.
     * Only meaningful in [TriggerMode.CUSTOM_STAGES] mode.
     */
    fun getResult(): ManualStageDialogResult {
        val stages = stageCheckboxes
            ?.filter { (_, cb) -> cb.isSelected }
            ?.map { (name, _) -> name }
            ?.toSet()
            ?: emptySet()
        val save = saveAsDefaultCheckbox?.isSelected ?: false
        return ManualStageDialogResult(selectedStages = stages, saveAsDefault = save)
    }

    private fun updateOkButton() {
        if (triggerMode != TriggerMode.CUSTOM_STAGES) return
        val hasSelection = stageCheckboxes?.any { (_, cb) -> cb.isSelected } ?: false
        okAction.isEnabled = hasSelection && !isLoading && !isLoadingStages
    }

    /**
     * Refills the dynamic slot panels (`stageSlot`, `varsSlot`) with their
     * post-load content. Crucially, this never touches the dialog's
     * `contentPanel` (DialogWrapper's `myContentPanel`) — the outer panel and
     * its position relative to the south button row stay intact.
     */
    private fun refillSlots() {
        stageSlot?.let { slot ->
            slot.removeAll()
            populateStageSlot(slot)
            slot.revalidate()
            slot.repaint()
        }
        varsSlot?.let { slot ->
            slot.removeAll()
            populateVariablesEditor(slot)
            slot.revalidate()
            slot.repaint()
        }
    }

    /** Modality-aware EDT dispatch. Platform `invokeLater` defaults to NON_MODAL from a
     *  background thread, which is suspended while this modal dialog is open — UI updates
     *  scheduled that way never fire until the dialog closes. */
    private fun invokeLater(runnable: Runnable) {
        val cp = this.contentPane
        val modality = if (cp != null) ModalityState.stateForComponent(cp) else ModalityState.any()
        platformInvokeLater(modality) { runnable.run() }
    }

    override fun createCenterPanel(): JComponent {
        val outer = JPanel()
        outer.layout = BoxLayout(outer, BoxLayout.Y_AXIS)
        outer.border = JBUI.Borders.empty(8)

        // CUSTOM_STAGES only: bound the dialog to a predictable size. Without
        // this a plan with many stages would push the south button panel below
        // the screen edge. The scroll panes inside each section absorb
        // overflow instead. STAGE/FULL_BUILD (Build tab) keeps content-driven
        // sizing — those modes don't show stage lists and have always sized to
        // the variable count without issue.
        if (triggerMode == TriggerMode.CUSTOM_STAGES) {
            val outerWidth = JBUI.scale(460)
            // Stages scroll (~260) + vars summary (~220 with header — bigger now) +
            // suite header (~36 when present) + save-default (~28) + borders (~32).
            // Without the vars preview, fall back to a shorter height.
            val outerHeight = JBUI.scale(when {
                variablesPreview != null && suiteDisplayName != null -> 590
                variablesPreview != null -> 560
                else -> 380
            })
            val preferred = Dimension(outerWidth, outerHeight)
            outer.preferredSize = preferred
            outer.minimumSize = preferred
        }

        // Suite header (CUSTOM_STAGES + suiteDisplayName only): non-editable
        // "Suite: <displayName> [(<planKey>)]" row at the very top so the user
        // can confirm which suite is about to fire without relying on the title bar.
        if (triggerMode == TriggerMode.CUSTOM_STAGES && suiteDisplayName != null) {
            val headerPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(2))).apply {
                border = BorderFactory.createCompoundBorder(
                    JBUI.Borders.emptyBottom(4),
                    BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor.border())
                )
                alignmentX = JComponent.LEFT_ALIGNMENT
            }
            headerPanel.add(JBLabel("Suite:").apply {
                font = font.deriveFont(Font.BOLD)
                foreground = com.workflow.orchestrator.core.ui.StatusColors.SECONDARY_TEXT
            })
            headerPanel.add(JBLabel(suiteDisplayName))
            // Show the plan key in dim secondary text when it differs from the display name
            // so the user can disambiguate suites that share similar names.
            if (planKey != suiteDisplayName) {
                headerPanel.add(JBLabel("($planKey)").apply {
                    foreground = com.workflow.orchestrator.core.ui.StatusColors.SECONDARY_TEXT
                })
            }
            outer.add(headerPanel)
        }

        // Stage selection section (CUSTOM_STAGES only).
        if (triggerMode == TriggerMode.CUSTOM_STAGES) {
            val slot = JPanel(GridBagLayout()).apply {
                border = JBUI.Borders.emptyBottom(8)
            }
            stageSlot = slot
            populateStageSlot(slot)
            val scrollPane = JBScrollPane(slot).apply {
                border = JBUI.Borders.empty()
                preferredSize = Dimension(JBUI.scale(420), JBUI.scale(260))
                horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            }
            outer.add(scrollPane)
        }

        // Variables summary (CUSTOM_STAGES only, when caller supplied a preview).
        // Rendered read-only — Automation-tab variables are edited on the tab,
        // not in the dialog. The preview is here so the user can verify what
        // will actually be sent before clicking Trigger.
        if (triggerMode == TriggerMode.CUSTOM_STAGES && variablesPreview != null) {
            outer.add(buildVariablesPreviewSection(variablesPreview))
        }

        // Variable editors section (FULL_BUILD / STAGE only — Build tab callers
        // that genuinely use the dialog as the variable edit surface).
        if (triggerMode != TriggerMode.CUSTOM_STAGES) {
            val slot = JPanel(GridBagLayout())
            varsSlot = slot
            populateVariablesEditor(slot)
            outer.add(slot)
        }

        // "Set this as default" checkbox — CUSTOM_STAGES only.
        if (triggerMode == TriggerMode.CUSTOM_STAGES) {
            val currentSelection = stageCheckboxes
                ?.filter { (_, cb) -> cb.isSelected }
                ?.map { (name, _) -> name }
                ?.toSet()
                ?: emptySet()
            val preChecked = savedDefaultStages != null && currentSelection == savedDefaultStages
            val cb = JBCheckBox("Set this as default", preChecked)
            saveAsDefaultCheckbox = cb
            val cbPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                border = JBUI.Borders.emptyTop(8)
                add(cb)
            }
            outer.add(cbPanel)
        }

        return outer
    }

    /**
     * Fills the supplied `panel` with the current stage-selection state:
     * loading spinner, error banner, "no stages" placeholder, or the actual
     * checkbox list. Called from both the initial layout build and from
     * [refillSlots] after async stages load.
     */
    private fun populateStageSlot(panel: JPanel) {
        val gbc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            anchor = GridBagConstraints.WEST
            insets = JBUI.insets(2)
            gridx = 0
            gridwidth = 1
            weightx = 1.0
        }

        if (isLoading || isLoadingStages) {
            gbc.gridy = 0
            val loadingPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
                add(JBLabel(AnimatedIcon.Default()))
                add(JBLabel("Loading stages..."))
            }
            panel.add(loadingPanel, gbc)
            return
        }

        val error = stageLoadError
        if (error != null) {
            gbc.gridy = 0
            panel.add(JBLabel("<html><b>Stage load failed</b><br>$error</html>").apply {
                foreground = JBColor.RED
            }, gbc)
            return
        }

        val checkboxes = stageCheckboxes
        if (checkboxes.isNullOrEmpty()) {
            gbc.gridy = 0
            panel.add(JBLabel("No stages found for plan $planKey."), gbc)
            return
        }

        gbc.gridy = 0
        panel.add(JBLabel("STAGES TO RUN").apply {
            font = font.deriveFont(java.awt.Font.BOLD, JBUI.scale(10).toFloat())
            foreground = com.workflow.orchestrator.core.ui.StatusColors.SECONDARY_TEXT
        }, gbc)

        checkboxes.forEachIndexed { idx, (_, cb) ->
            gbc.gridy = idx + 1
            panel.add(cb, gbc)
        }
    }

    /**
     * Read-only preview of variables that will be sent with the trigger.
     * Used in CUSTOM_STAGES mode to show the merged Automation-tab var map
     * (suiteConfigPanel variable overrides + dockerTagsAsJson). Uses a JTable
     * inside a JBScrollPane so a long var list (or a large dockerTagsAsJson JSON)
     * is scrollable and fully readable without pushing the dialog past its bounded
     * height. Rows are sorted alphabetically by key; cells are read-only and
     * select-and-copy friendly. Value column uses monospace font; long JSON values
     * wrap within the cell so vertical scrolling works.
     */
    private fun buildVariablesPreviewSection(vars: Map<String, String>): JComponent {
        val container = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.emptyTop(4)
        }

        container.add(JBLabel("VARIABLES TO SEND").apply {
            font = font.deriveFont(Font.BOLD, JBUI.scale(10).toFloat())
            foreground = com.workflow.orchestrator.core.ui.StatusColors.SECONDARY_TEXT
            border = JBUI.Borders.emptyBottom(4)
            alignmentX = JComponent.LEFT_ALIGNMENT
        })

        if (vars.isEmpty()) {
            container.add(JBLabel("(no variables — trigger will use plan defaults)").apply {
                foreground = com.workflow.orchestrator.core.ui.StatusColors.SECONDARY_TEXT
                alignmentX = JComponent.LEFT_ALIGNMENT
            })
            return container
        }

        // Sort entries alphabetically by key so dockerTagsAsJson always lands in a
        // predictable position and new keys don't shuffle the list on re-open.
        val sortedEntries = vars.entries.sortedBy { it.key }

        val tableModel = VariablesTableModel(sortedEntries)
        val table = JBTable(tableModel).apply {
            // Read-only — variables are edited on the Automation tab, not here.
            setDefaultEditor(Any::class.java, null)
            // Allow row selection so the user can copy individual rows with Ctrl+C.
            setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION)
            rowSelectionAllowed = true
            columnSelectionAllowed = false
            // Key column: fixed preferred width; value column: takes remaining space.
            columnModel.getColumn(0).apply {
                preferredWidth = JBUI.scale(160)
                minWidth = JBUI.scale(80)
                maxWidth = JBUI.scale(220)
                // Bold header-style renderer for the key column.
                cellRenderer = DefaultTableCellRenderer().apply {
                    font = font.deriveFont(Font.BOLD)
                }
            }
            columnModel.getColumn(1).apply {
                preferredWidth = JBUI.scale(260)
                // Monospace renderer for values; HTML-wraps long lines so the row
                // grows in height rather than clipping content horizontally.
                cellRenderer = WrappingMonospaceCellRenderer()
            }
            // Let rows grow in height to fit wrapped content.
            setRowHeight(JBUI.scale(20))
            // Remove the outer table border — the scroll pane provides the border.
            border = JBUI.Borders.empty()
            // Ensure the table fills the viewport width so the value column is flex.
            fillsViewportHeight = false
            autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN
            // P2-20: debounce the full-table re-measure so rapid resize events
            // (e.g. user dragging the dialog edge) do not trigger O(rows*cols)
            // renderer calls on every pixel. RESIZE_DEBOUNCE_MS is a one-shot
            // swing Timer restarted on each resize event; only the last event in
            // a burst triggers the recompute.
            //
            // The FIRST resize (fired by the initial layout when the dialog opens)
            // is measured immediately — debouncing it would render wrapped rows
            // clipped for RESIZE_DEBOUNCE_MS on open (W6-D3 review M3). Only
            // subsequent resizes (user drags) are debounced.
            val resizeDebounce = javax.swing.Timer(RESIZE_DEBOUNCE_MS) { updateRowHeights(this@apply) }
                .apply { isRepeats = false }
            addComponentListener(object : java.awt.event.ComponentAdapter() {
                private var firstMeasureDone = false

                override fun componentResized(e: java.awt.event.ComponentEvent) {
                    if (!firstMeasureDone) {
                        firstMeasureDone = true
                        updateRowHeights(this@apply)
                    } else {
                        resizeDebounce.restart()
                    }
                }
            })
        }

        val scroll = JBScrollPane(table).apply {
            border = BorderFactory.createLineBorder(JBColor.border())
            preferredSize = Dimension(JBUI.scale(440), JBUI.scale(220))
            horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        }
        scroll.alignmentX = JComponent.LEFT_ALIGNMENT
        container.add(scroll)
        return container
    }

    /**
     * Recalculates the row height for each row in [table] based on the preferred
     * height of the rendered value cell (which may wrap to multiple lines). Must
     * be called after the table has been laid out so column widths are known.
     */
    private fun updateRowHeights(table: JTable) {
        for (row in 0 until table.rowCount) {
            var maxHeight = table.rowHeight
            for (col in 0 until table.columnCount) {
                val renderer = table.getCellRenderer(row, col)
                val comp = table.prepareRenderer(renderer, row, col)
                val preferred = comp.preferredSize.height
                if (preferred > maxHeight) maxHeight = preferred
            }
            if (table.getRowHeight(row) != maxHeight) {
                table.setRowHeight(row, maxHeight)
            }
        }
    }

    /**
     * Table model for the variables preview. Two columns: Key (String) and Value (String).
     * All cells are read-only. The [entries] list must already be sorted by the caller.
     */
    internal class VariablesTableModel(
        private val entries: List<Map.Entry<String, String>>
    ) : AbstractTableModel() {
        override fun getRowCount() = entries.size
        override fun getColumnCount() = 2
        override fun getColumnName(col: Int) = if (col == 0) "Key" else "Value"
        override fun getColumnClass(col: Int) = String::class.java
        override fun isCellEditable(row: Int, col: Int) = false
        override fun getValueAt(row: Int, col: Int): Any {
            val entry = entries[row]
            return when (col) {
                0 -> entry.key
                else -> entry.value.ifBlank { "(empty)" }
            }
        }
    }

    /**
     * Cell renderer that displays text in a monospace font and wraps long values
     * using an HTML `div` with a fixed pixel width. This causes the JTable row to
     * grow in height (via [updateRowHeights]) rather than clipping content.
     */
    private inner class WrappingMonospaceCellRenderer : DefaultTableCellRenderer() {
        private val monoFont = Font(Font.MONOSPACED, Font.PLAIN, JBUI.scale(11))

        override fun getTableCellRendererComponent(
            table: JTable, value: Any?,
            isSelected: Boolean, hasFocus: Boolean,
            row: Int, column: Int
        ): java.awt.Component {
            val raw = value?.toString() ?: ""
            val escaped = com.workflow.orchestrator.core.util.HtmlEscape.escapeHtml(raw)
            // Wrap at ~300px (column flex width). Using a fixed px value here is safe
            // because the scroll pane has HORIZONTAL_SCROLLBAR_NEVER and the value
            // column always fills the remaining space after the 160px key column.
            val html = "<html><div style='width:${JBUI.scale(280)}px;font-family:monospace'>$escaped</div></html>"
            val comp = super.getTableCellRendererComponent(table, html, isSelected, hasFocus, row, column)
            comp.font = monoFont
            return comp
        }
    }

    /**
     * Fills `panel` with the variable editor rows for FULL_BUILD/STAGE modes.
     * Called from createCenterPanel and refillSlots.
     */
    private fun populateVariablesEditor(panel: JPanel) {
        variableEditors.clear()
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
            return
        }

        variables.forEachIndexed { index, variable ->
            gbc.gridy = index

            gbc.gridx = 0
            gbc.weightx = 0.0
            gbc.gridwidth = 1
            panel.add(JBLabel("${variable.name}:"), gbc)

            // Editor selection priority:
            //   1. Password variables → JBPasswordField
            //   2. Boolean-like values → JBCheckBox
            //   3. Everything else    → JBTextField
            gbc.gridx = 1
            gbc.weightx = 1.0
            val editor: JComponent = when {
                variable.isPassword -> JBPasswordField().apply {
                    text = variable.value
                }
                variable.value in listOf("true", "false") -> JBCheckBox().apply {
                    isSelected = variable.value == "true"
                }
                else -> JBTextField(variable.value, 20)
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
    }

    override fun doOKAction() {
        val vars = variableEditors.mapValues { (_, editor) ->
            when (editor) {
                // JBPasswordField is a JBTextField subclass — match it FIRST so we
                // pull the password via getPassword() (clears chars on dispose) and
                // never via .text (which getPassword's contract avoids returning).
                is JBPasswordField -> String(editor.password)
                is JBCheckBox -> editor.isSelected.toString()
                is JBTextField -> editor.text
                else -> ""
            }
        }

        // Audit P1 (PR 7 #1) — never log password variable values. Build a
        // log-safe view of the variable map by name with secrets redacted.
        val passwordKeys = variables.filter { it.isPassword }.map { it.name }.toSet()
        if (passwordKeys.isNotEmpty()) {
            val safeKeys = vars.keys.joinToString { name ->
                if (name in passwordKeys) "$name=<redacted>" else name
            }
            log.info("[Bamboo:UI] Triggering with variables: $safeKeys")
        }

        // CUSTOM_STAGES: the caller reads getResult() after show() returns.
        // The dialog does NOT trigger the build itself in this mode — the caller owns
        // the trigger call so it can combine stages with the queue service.
        if (triggerMode == TriggerMode.STAGE) {
            scope.launch {
                bambooService.triggerStage(planKey, vars, stageName)
            }
        }

        super.doOKAction()
    }

    /** Test seam — exposes the loaded variables list so unit tests can inspect
     *  the password masking decision without spinning up the full dialog. */
    internal fun variablesForTest(): List<PlanVariableData> = variables

    /** Test seam — exposes the rendered editor classes so a unit test can
     *  assert that a password variable produces a `JBPasswordField` (not a
     *  plain `JBTextField`) without driving the EDT. */
    internal fun editorsForTest(): Map<String, JComponent> = variableEditors

    /** Test seam — exposes stage checkbox state for CUSTOM_STAGES mode tests. */
    internal fun stageCheckboxesForTest(): List<Pair<String, JBCheckBox>>? = stageCheckboxes

    /** Test seam — exposes stage load error for CUSTOM_STAGES tests. */
    internal fun stageLoadErrorForTest(): String? = stageLoadError
}

package com.workflow.orchestrator.bamboo.ui

import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.invokeLater as platformInvokeLater
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.core.model.bamboo.PlanVariableData
import com.workflow.orchestrator.core.services.BambooService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Trigger mode for [ManualStageDialog].
 *
 * - [STAGE] — trigger a single named manual stage (original dialog behaviour).
 * - [FULL_BUILD] — trigger the full plan (all stages). Legacy escape hatch;
 *   most callers should use [CUSTOM_STAGES] or the split-button on the Automation tab.
 * - [CUSTOM_STAGES] — show a checkbox list of all plan stages, let the user pick one
 *   or more. Pre-checks: saved default if any; else first stage; else nothing.
 *   OK button disabled when nothing is selected or stage load failed.
 *   On confirm, the selected stage names + optional "save as default" flag are returned
 *   via [ManualStageDialog.getResult].
 */
enum class TriggerMode {
    STAGE,
    /** Legacy escape hatch — most callers should use CUSTOM_STAGES. */
    FULL_BUILD,
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
 */
class ManualStageDialog(
    private val project: Project,
    private val planKey: String,
    private val stageName: String = "",
    private val scope: CoroutineScope,
    private val triggerMode: TriggerMode = TriggerMode.STAGE,
    private val savedDefaultStages: Set<String>? = null
) : DialogWrapper(project) {

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

    init {
        title = when (triggerMode) {
            TriggerMode.FULL_BUILD -> "Trigger Build"
            TriggerMode.STAGE -> "Run Stage: $stageName"
            TriggerMode.CUSTOM_STAGES -> "Trigger Customized Build"
        }
        setOKButtonText(when (triggerMode) {
            TriggerMode.FULL_BUILD -> "Trigger"
            TriggerMode.STAGE -> "OK"
            TriggerMode.CUSTOM_STAGES -> "Trigger"
        })
        // OK disabled until at least one stage is checked (CUSTOM_STAGES only).
        if (triggerMode == TriggerMode.CUSTOM_STAGES) {
            okAction.isEnabled = false
        }
        init()

        // Load variables and (for CUSTOM_STAGES) plan stages asynchronously.
        scope.launch {
            val varResult = bambooService.getPlanVariables(planKey)
            if (!varResult.isError) {
                variables = varResult.data!!
            }

            if (triggerMode == TriggerMode.CUSTOM_STAGES) {
                // Fetch stages from the latest build result for this plan.
                // H6: on load failure, show banner and disable OK — no silent "run all".
                val stagesResult = bambooService.getLatestBuild(planKey)
                isLoadingStages = false
                if (!stagesResult.isError && stagesResult.data != null) {
                    val stageNames = stagesResult.data!!.stages.map { it.name }
                    if (stageNames.isNotEmpty()) {
                        stageCheckboxes = stageNames.map { name ->
                            // Pre-check logic (H6): saved default if any; else first stage; else nothing.
                            val preChecked = when {
                                savedDefaultStages != null -> name in savedDefaultStages
                                else -> name == stageNames.first()
                            }
                            val cb = JBCheckBox(name, preChecked)
                            cb.addActionListener { updateOkButton() }
                            name to cb
                        }
                    } else {
                        stageLoadError = "No stage information available for plan $planKey. " +
                            "Trigger a full build first so Bamboo records the stage list."
                    }
                } else {
                    // H6: stage load failure — banner + OK stays disabled. Never silently runs all.
                    stageLoadError = "Couldn't load stages from Bamboo: ${stagesResult.summary}. " +
                        "Refresh, or cancel."
                }
            }

            isLoading = false
            invokeLater {
                rebuildForm()
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

    private fun rebuildForm() {
        contentPanel?.removeAll()
        contentPanel?.add(createCenterPanel())
        contentPanel?.revalidate()
        contentPanel?.repaint()
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

        // Stage selection section (CUSTOM_STAGES only).
        if (triggerMode == TriggerMode.CUSTOM_STAGES) {
            val stageSection = buildStageSection()
            outer.add(stageSection)
        }

        // Variable editors section — only for FULL_BUILD / STAGE modes (Build tab).
        // CUSTOM_STAGES (Automation tab) sources variables from the panel's own
        // suiteConfigPanel + suiteExtrasPanel; the dialog is stages-only there.
        if (triggerMode != TriggerMode.CUSTOM_STAGES) {
            outer.add(buildVariablesSection())
        }

        // "Set this as default" checkbox — only in CUSTOM_STAGES mode.
        // Unchecked → default not saved → next default-click opens this dialog again.
        // Checked   → save selection as suite default → next default-click skips dialog.
        // To change/clear the default, user clicks "Trigger Customized…".
        if (triggerMode == TriggerMode.CUSTOM_STAGES) {
            val currentSelection = stageCheckboxes
                ?.filter { (_, cb) -> cb.isSelected }
                ?.map { (name, _) -> name }
                ?.toSet()
                ?: emptySet()
            // Pre-check if the current selection exactly matches the saved default.
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

    private fun buildStageSection(): JComponent {
        val panel = JPanel(GridBagLayout())
        panel.border = JBUI.Borders.emptyBottom(8)

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
            return panel
        }

        val error = stageLoadError
        if (error != null) {
            gbc.gridy = 0
            panel.add(JBLabel("<html><b>Stage load failed</b><br>$error</html>").apply {
                foreground = com.intellij.ui.JBColor.RED
            }, gbc)
            return panel
        }

        val checkboxes = stageCheckboxes
        if (checkboxes.isNullOrEmpty()) {
            gbc.gridy = 0
            panel.add(JBLabel("No stages found for plan $planKey."), gbc)
            return panel
        }

        gbc.gridy = 0
        panel.add(JBLabel("STAGES TO RUN:").apply {
            font = font.deriveFont(java.awt.Font.BOLD, JBUI.scale(10).toFloat())
            foreground = com.workflow.orchestrator.core.ui.StatusColors.SECONDARY_TEXT
        }, gbc)

        checkboxes.forEachIndexed { idx, (_, cb) ->
            gbc.gridy = idx + 1
            panel.add(cb, gbc)
        }

        return panel
    }

    private fun buildVariablesSection(): JComponent {
        val panel = JPanel(GridBagLayout())

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

            // Editor selection priority (PR 7 audit P1 #1):
            //   1. Password variables → JBPasswordField (value never echoed to log)
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

        return panel
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
            // Note: .info on the dialog log channel is intentional — we want a
            // breadcrumb that the trigger fired without leaking the secret.
            log.info("[Bamboo:UI] Triggering with variables: $safeKeys")
        }

        // CUSTOM_STAGES: the caller reads getSelectedStages() after show() returns.
        // The dialog does NOT trigger the build itself in this mode — the caller owns
        // the trigger call so it can combine stages with the queue service.
        if (triggerMode != TriggerMode.CUSTOM_STAGES) {
            scope.launch {
                when (triggerMode) {
                    TriggerMode.FULL_BUILD -> bambooService.triggerBuild(planKey, vars, stages = null)
                    TriggerMode.STAGE -> bambooService.triggerStage(planKey, vars, stageName)
                    TriggerMode.CUSTOM_STAGES -> { /* handled by caller */ }
                }
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

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
 *   or more. Pre-checks the first stage (Bamboo UI default). OK button disabled when
 *   nothing is selected. On confirm, the selected stage names are returned via
 *   [ManualStageDialog.getSelectedStages].
 */
enum class TriggerMode {
    STAGE,
    /** Legacy escape hatch — most callers should use CUSTOM_STAGES. */
    FULL_BUILD,
    CUSTOM_STAGES
}

/**
 * Dialog for triggering a Bamboo build stage or full build, with optional
 * variable overrides and (in [TriggerMode.CUSTOM_STAGES] mode) stage selection.
 *
 * In CUSTOM_STAGES mode, the caller is responsible for reading [getSelectedStages]
 * after `show()` returns and using those names to construct a trigger call.
 * The dialog itself does NOT call [BambooService.triggerBuild] in CUSTOM_STAGES mode
 * — it only collects the selection.
 */
class ManualStageDialog(
    private val project: Project,
    private val planKey: String,
    private val stageName: String = "",
    private val scope: CoroutineScope,
    private val triggerMode: TriggerMode = TriggerMode.STAGE
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
                val stagesResult = bambooService.getLatestBuild(planKey)
                isLoadingStages = false
                if (!stagesResult.isError && stagesResult.data != null) {
                    val stageNames = stagesResult.data!!.stages.map { it.name }
                    if (stageNames.isNotEmpty()) {
                        stageCheckboxes = stageNames.mapIndexed { idx, name ->
                            val cb = JBCheckBox(name, idx == 0) // first stage pre-checked
                            cb.addActionListener { updateOkButton() }
                            name to cb
                        }
                    } else {
                        stageLoadError = "No stage information available for plan $planKey. " +
                            "Trigger a full build first so Bamboo records the stage list."
                    }
                } else {
                    stageLoadError = "Could not load stage list: ${stagesResult.summary}. " +
                        "Check Bamboo connection in Settings."
                }
            }

            isLoading = false
            invokeLater {
                rebuildForm()
                updateOkButton()
            }
        }
    }

    /** Returns the set of stage names the user checked. Only meaningful in CUSTOM_STAGES mode. */
    fun getSelectedStages(): Set<String> =
        stageCheckboxes
            ?.filter { (_, cb) -> cb.isSelected }
            ?.map { (name, _) -> name }
            ?.toSet()
            ?: emptySet()

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

        // Variable editors section.
        outer.add(buildVariablesSection())

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

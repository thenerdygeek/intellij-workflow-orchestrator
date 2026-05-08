package com.workflow.orchestrator.bamboo.ui

import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.invokeLater as platformInvokeLater
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
import javax.swing.JComponent
import javax.swing.JPanel

enum class TriggerMode {
    STAGE,
    FULL_BUILD
}

class ManualStageDialog(
    private val project: Project,
    private val planKey: String,
    private val stageName: String = "",
    private val scope: CoroutineScope,
    private val triggerMode: TriggerMode = TriggerMode.STAGE
) : DialogWrapper(project) {

    private val bambooService = project.getService(BambooService::class.java)
    private val variableEditors = mutableMapOf<String, JComponent>()
    private var variables: List<PlanVariableData> = emptyList()
    private var isLoading = true

    init {
        title = when (triggerMode) {
            TriggerMode.FULL_BUILD -> "Trigger Build"
            TriggerMode.STAGE -> "Run Stage: $stageName"
        }
        setOKButtonText(when (triggerMode) {
            TriggerMode.FULL_BUILD -> "Trigger"
            TriggerMode.STAGE -> "OK"
        })
        init()
        // Load variables asynchronously after dialog is shown
        scope.launch {
            val result = bambooService.getPlanVariables(planKey)
            if (!result.isError) {
                variables = result.data!!
            }
            isLoading = false
            invokeLater { rebuildForm() }
        }
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
        val panel = JPanel(GridBagLayout())
        panel.border = JBUI.Borders.empty(8)

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
            com.intellij.openapi.diagnostic.Logger.getInstance(ManualStageDialog::class.java)
                .info("[Bamboo:UI] Triggering with variables: $safeKeys")
        }

        scope.launch {
            when (triggerMode) {
                TriggerMode.FULL_BUILD -> bambooService.triggerBuild(planKey, vars)
                TriggerMode.STAGE -> bambooService.triggerStage(planKey, vars, stageName)
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
}

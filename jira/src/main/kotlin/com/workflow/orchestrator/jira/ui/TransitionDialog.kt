package com.workflow.orchestrator.jira.ui

import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.core.services.JiraService
import com.workflow.orchestrator.jira.api.dto.JiraTransition
import com.workflow.orchestrator.jira.api.dto.JiraTransitionFieldMeta
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import kotlinx.coroutines.*
import java.awt.BorderLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.*

/**
 * Dialog shown when a Jira transition has mandatory fields.
 * Renders form fields based on the transition's field metadata.
 */
class TransitionDialog(
    private val project: Project,
    private val issueKey: String,
    private val transition: JiraTransition,
    private val onTransitioned: () -> Unit
) : DialogWrapper(project, true) {

    private val log = Logger.getInstance(TransitionDialog::class.java)
    private val fieldInputs = mutableMapOf<String, () -> Any?>()
    private val commentArea = JBTextArea(3, 40).apply { lineWrap = true; wrapStyleWord = true }
    private val resultLabel = JBLabel("")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        title = "Transition $issueKey to ${transition.name}"
        setOKButtonText("Transition")
        init()
        Disposer.register(disposable, Disposable { scope.cancel() })
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, JBUI.scale(12)))
        panel.border = JBUI.Borders.empty(8)
        panel.preferredSize = java.awt.Dimension(JBUI.scale(420), JBUI.scale(300))

        // Required fields
        val requiredFields = transition.fields?.filter { it.value.required } ?: emptyMap()

        if (requiredFields.isNotEmpty()) {
            val fieldsPanel = JPanel(GridBagLayout())
            val gbc = GridBagConstraints().apply {
                fill = GridBagConstraints.HORIZONTAL
                insets = JBUI.insets(4, 0)
            }

            var row = 0
            for ((fieldId, meta) in requiredFields) {
                gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0
                fieldsPanel.add(JBLabel("${meta.name}: *"), gbc)

                gbc.gridx = 1; gbc.weightx = 1.0
                val input = createFieldInput(fieldId, meta)
                fieldsPanel.add(input, gbc)
                row++
            }

            panel.add(fieldsPanel, BorderLayout.NORTH)
        }

        // Optional comment
        val commentPanel = JPanel(BorderLayout(0, JBUI.scale(4)))
        commentPanel.add(JBLabel("Comment (optional):"), BorderLayout.NORTH)
        commentPanel.add(JScrollPane(commentArea), BorderLayout.CENTER)
        panel.add(commentPanel, BorderLayout.CENTER)

        panel.add(resultLabel, BorderLayout.SOUTH)

        return panel
    }

    private fun createFieldInput(fieldId: String, meta: JiraTransitionFieldMeta): JComponent {
        val allowedValues = meta.allowedValues

        return if (allowedValues != null && allowedValues.isNotEmpty()) {
            // Dropdown
            val combo = JComboBox(allowedValues.map { it.name }.toTypedArray())
            fieldInputs[fieldId] = {
                val selectedName = combo.selectedItem as? String
                allowedValues.find { it.name == selectedName }?.let { mapOf("name" to it.name) }
            }
            combo
        } else {
            // Text field
            val textField = JBTextField()
            fieldInputs[fieldId] = { textField.text.takeIf { it.isNotBlank() } }
            textField
        }
    }

    override fun doOKAction() {
        // Validate required fields
        val requiredFields = transition.fields?.filter { it.value.required } ?: emptyMap()
        for ((fieldId, meta) in requiredFields) {
            val value = fieldInputs[fieldId]?.invoke()
            if (value == null) {
                resultLabel.text = "${meta.name} is required"
                resultLabel.foreground = JBColor.RED
                return
            }
        }

        isOKActionEnabled = false
        resultLabel.text = "Transitioning..."
        resultLabel.foreground = JBColor.foreground()

        val jiraService = project.getService(JiraService::class.java)

        // Build fields map
        val fields = mutableMapOf<String, Any>()
        for ((fieldId, getter) in fieldInputs) {
            val value = getter() ?: continue
            fields[fieldId] = value
        }

        val comment = commentArea.text.takeIf { it.isNotBlank() }

        scope.launch {
            val result = jiraService.transition(
                key = issueKey,
                transitionId = transition.id,
                fields = fields.takeIf { it.isNotEmpty() },
                comment = comment
            )

            invokeLater {
                if (!result.isError) {
                    log.info("[Jira:Transition] $issueKey transitioned to ${transition.name}")
                    onTransitioned()
                    close(OK_EXIT_CODE)
                } else {
                    isOKActionEnabled = true
                    resultLabel.text = "Failed: ${result.summary}"
                    resultLabel.foreground = JBColor.RED
                }
            }
        }
    }
}

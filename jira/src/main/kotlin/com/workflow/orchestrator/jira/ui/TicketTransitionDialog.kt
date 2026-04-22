package com.workflow.orchestrator.jira.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.core.model.jira.TransitionInput
import com.workflow.orchestrator.core.model.jira.TransitionMeta
import com.workflow.orchestrator.core.services.jira.JiraSearchService
import com.workflow.orchestrator.core.services.jira.TicketTransitionService
import com.workflow.orchestrator.jira.ui.widgets.FieldWidget
import com.workflow.orchestrator.jira.ui.widgets.FieldWidgetFactory
import com.workflow.orchestrator.jira.ui.widgets.WidgetContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * Shared [DialogWrapper] that:
 * 1. Loads available transitions for [ticketKey] from [TicketTransitionService].
 * 2. Lets the user pick a transition (pre-selected to [initialTransitionId] when provided).
 * 3. Renders required/optional fields via [FieldWidgetFactory].
 * 4. Validates all required fields before submission.
 * 5. Executes the chosen transition via [TicketTransitionService.executeTransition].
 *
 * Intended as the single shared transition dialog, replacing the legacy [TransitionDialog].
 * Caller migration is handled in T28; the old file remains until T30/T31.
 */
class TicketTransitionDialog(
    private val project: Project,
    private val ticketKey: String,
    private val projectKey: String,
    private val initialTransitionId: String? = null
) : DialogWrapper(project, true), Disposable {

    private val log = Logger.getInstance(TicketTransitionDialog::class.java)
    private val transitionService = project.service<TicketTransitionService>()
    private val searchService = project.service<JiraSearchService>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var transitions: List<TransitionMeta> = emptyList()
    private var selected: TransitionMeta? = null
    private val widgetsById = mutableMapOf<String, FieldWidget>()
    private val commentArea = JBTextArea(3, 40).apply { lineWrap = true; wrapStyleWord = true }
    private val fieldPanel = JPanel(GridBagLayout())
    private val transitionCombo = ComboBox<String>()

    init {
        title = "Transition $ticketKey"
        setOKButtonText("Transition")
        transitionCombo.addActionListener { selectTransition(transitionCombo.selectedIndex) }
        init()
        loadTransitions()
    }

    override fun createCenterPanel(): JComponent {
        val root = JPanel(BorderLayout(0, JBUI.scale(8)))
        root.border = JBUI.Borders.empty(8)
        root.preferredSize = java.awt.Dimension(JBUI.scale(460), JBUI.scale(340))

        val top = JPanel(BorderLayout(JBUI.scale(8), 0))
        top.add(JLabel("To:"), BorderLayout.WEST)
        top.add(transitionCombo, BorderLayout.CENTER)
        root.add(top, BorderLayout.NORTH)

        root.add(JBScrollPane(fieldPanel), BorderLayout.CENTER)

        val bottom = JPanel(BorderLayout(0, JBUI.scale(4)))
        bottom.add(JLabel("Comment (optional):"), BorderLayout.NORTH)
        bottom.add(JBScrollPane(commentArea), BorderLayout.CENTER)
        root.add(bottom, BorderLayout.SOUTH)

        return root
    }

    // ---------------------------------------------------------------------------
    // Data loading
    // ---------------------------------------------------------------------------

    private fun loadTransitions() {
        scope.launch {
            val result = transitionService.getAvailableTransitions(ticketKey)
            invokeLater {
                if (!result.isError) {
                    val list = result.data ?: emptyList()
                    transitions = list
                    transitionCombo.removeAllItems()
                    transitions.forEach { transitionCombo.addItem(it.toStatus.name) }
                    val idx = transitions
                        .indexOfFirst { it.id == initialTransitionId }
                        .coerceAtLeast(0)
                    if (transitions.isNotEmpty()) {
                        transitionCombo.selectedIndex = idx
                    }
                } else {
                    setErrorText(result.summary.ifBlank { "Failed to load transitions" })
                }
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Transition selection + field panel rebuild
    // ---------------------------------------------------------------------------

    private fun selectTransition(idx: Int) {
        if (idx < 0 || idx >= transitions.size) return
        val t = transitions[idx]
        selected = t
        rebuildFieldPanel(t)
    }

    private fun rebuildFieldPanel(t: TransitionMeta) {
        fieldPanel.removeAll()
        widgetsById.clear()

        val ctx = WidgetContext(
            project = project,
            ticketKey = ticketKey,
            projectKey = projectKey,
            search = searchService,
            disposable = this
        )

        val c = GridBagConstraints().apply {
            gridx = 0
            gridy = 0
            anchor = GridBagConstraints.NORTHWEST
            fill = GridBagConstraints.HORIZONTAL
            weightx = 1.0
            insets = JBUI.insets(2)
        }

        // Required fields first, then optional
        t.fields.sortedByDescending { it.required }.forEach { field ->
            val label = JLabel((if (field.required) "* " else "") + field.name)
            c.gridx = 0
            c.weightx = 0.0
            fieldPanel.add(label, c)

            val widget = FieldWidgetFactory.build(field, ctx) { /* validation on OK */ }
            widget.setInitial(field.defaultValue)
            widgetsById[field.id] = widget

            c.gridx = 1
            c.weightx = 1.0
            fieldPanel.add(widget.component, c)

            c.gridy++
        }

        fieldPanel.revalidate()
        fieldPanel.repaint()
    }

    // ---------------------------------------------------------------------------
    // Validation
    // ---------------------------------------------------------------------------

    override fun doValidate(): ValidationInfo? {
        widgetsById.values.forEach { widget ->
            val err = widget.validate()
            if (err != null) return ValidationInfo(err, widget.component)
        }
        return null
    }

    // ---------------------------------------------------------------------------
    // Submission
    // ---------------------------------------------------------------------------

    override fun doOKAction() {
        val t = selected ?: run {
            setErrorText("No transition selected")
            return
        }

        val values = widgetsById
            .mapNotNull { (id, widget) -> widget.currentValue()?.let { id to it } }
            .toMap()

        val input = TransitionInput(
            transitionId = t.id,
            fieldValues = values,
            comment = commentArea.text.takeIf { it.isNotBlank() }
        )

        isOKActionEnabled = false

        scope.launch {
            val result = transitionService.executeTransition(ticketKey, input)
            invokeLater {
                if (!result.isError) {
                    log.info("[Jira:Transition] $ticketKey → ${t.toStatus.name} (${t.id})")
                    super@TicketTransitionDialog.doOKAction()
                } else {
                    isOKActionEnabled = true
                    setErrorText(result.summary.ifBlank { "Transition failed" })
                }
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------

    override fun dispose() {
        scope.cancel()
        super.dispose()
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    /**
     * Modality-aware EDT dispatch. Using [ModalityState.stateForComponent] ensures that
     * `invokeLater` calls fire even while this modal dialog is open (NON_MODAL would be
     * suspended until the dialog closes).
     */
    private fun invokeLater(block: () -> Unit) {
        val cp = contentPanel
        val modality = if (cp != null) ModalityState.stateForComponent(cp) else ModalityState.any()
        ApplicationManager.getApplication().invokeLater(block, modality)
    }
}

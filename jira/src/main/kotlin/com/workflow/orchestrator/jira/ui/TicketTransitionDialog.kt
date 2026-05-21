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
import com.workflow.orchestrator.core.ui.ComboBoxWidth
import com.workflow.orchestrator.core.ui.bindBoundedWidth
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
 * This is the single shared transition dialog; the legacy TransitionDialog has been removed.
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
    private val transitionCombo = ComboBox<String>().apply {
        bindBoundedWidth(ComboBoxWidth.WIDE)
    }

    // Guards the combo's actionListener while [loadTransitions] rebuilds the model.
    // Without this, the first `addItem` flips selection from -1 to 0 and fires
    // [selectTransition], which would race the explicit pre-selection logic below.
    private var suppressComboEvents = false

    init {
        title = "Transition $ticketKey"
        setOKButtonText("Transition")
        transitionCombo.addActionListener { selectTransition(transitionCombo.selectedIndex) }
        init()
        // Start with OK disabled; [loadTransitions] re-enables it once a valid
        // pre-selection is resolved (either the caller's target id or, when the
        // caller passed null, the first available transition).
        isOKActionEnabled = false
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
                if (result.isError) {
                    setErrorText(result.summary.ifBlank { "Failed to load transitions" })
                    return@invokeLater
                }
                val list = result.data ?: emptyList()
                transitions = list

                // Rebuild combo items without firing the actionListener — the first
                // addItem auto-selects index 0 and would otherwise commit a stale
                // selection before the resolution logic below picks the right one.
                suppressComboEvents = true
                try {
                    transitionCombo.removeAllItems()
                    transitions.forEach { transitionCombo.addItem(it.toStatus.name) }
                } finally {
                    suppressComboEvents = false
                }

                val preIdx = resolveInitialSelectionIndex(transitions, initialTransitionId)
                when {
                    transitions.isEmpty() -> {
                        setErrorText("No transitions available for this ticket.")
                        // OK stays disabled (set in init).
                    }
                    preIdx != null -> {
                        // Either the caller's target id matched, or no target was
                        // specified and we default to the first transition. Setting
                        // selectedIndex fires the actionListener which sets `selected`
                        // and enables OK.
                        transitionCombo.selectedIndex = preIdx
                    }
                    else -> {
                        // Caller asked for a specific transition that isn't in the
                        // current available set. Don't silently swap to index 0 —
                        // historically that produced "I clicked Start Work but the
                        // ticket went to In Review" bugs, because the Start Work
                        // target ("In Progress") wasn't a configured transition for
                        // the ticket's current status (either due to a Jira-side
                        // automation moving the ticket between fetch and dialog open,
                        // or a status-name mismatch with the user's setting). Require
                        // an explicit pick.
                        transitionCombo.selectedIndex = -1
                        setErrorText(
                            "The configured Start Work target isn't a valid transition from " +
                                "this ticket's current status. Pick a transition to proceed."
                        )
                        // OK stays disabled until the user actively selects.
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Transition selection + field panel rebuild
    // ---------------------------------------------------------------------------

    private fun selectTransition(idx: Int) {
        if (suppressComboEvents) return
        if (idx < 0 || idx >= transitions.size) {
            // No real selection — leave OK in whatever state loadTransitions set.
            selected = null
            return
        }
        val t = transitions[idx]
        selected = t
        rebuildFieldPanel(t)
        // The user (or the resolution logic) committed to a transition: clear any
        // "pick one" warning and enable OK.
        setErrorText(null)
        isOKActionEnabled = true
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

    companion object {
        /**
         * Pure helper: resolves which combo index to pre-select when the dialog loads.
         *
         *  - `null` when there are no transitions at all OR when [initialTransitionId]
         *    was specified by the caller but doesn't appear in [transitions]. The
         *    caller must distinguish these two by inspecting `transitions.isEmpty()`.
         *  - `0` when [initialTransitionId] is null — caller didn't request a target,
         *    so default to the first available transition (this preserves the existing
         *    UX for the TicketDetailPanel "transition" button and the post-commit
         *    notification path).
         *  - the matched index when [initialTransitionId] is found.
         *
         * Extracted so the "no silent fallback to index 0 when requested target is
         * missing" guarantee can be tested without spinning up `BasePlatformTestCase`.
         */
        internal fun resolveInitialSelectionIndex(
            transitions: List<TransitionMeta>,
            initialTransitionId: String?
        ): Int? {
            if (transitions.isEmpty()) return null
            if (initialTransitionId == null) return 0
            val idx = transitions.indexOfFirst { it.id == initialTransitionId }
            return if (idx >= 0) idx else null
        }
    }
}

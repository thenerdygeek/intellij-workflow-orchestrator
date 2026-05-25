package com.workflow.orchestrator.agent.delegation.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.DialogWrapper.IdeModalityType
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.core.delegation.DelegationMessage
import java.awt.BorderLayout
import java.awt.event.ActionEvent
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JPanel

/** The user's verdict on an inbound delegation doorbell ([DelegationMessage.Knock]). */
enum class ConsentChoice { ALLOW_ONCE, ALLOW_ALWAYS, CANCEL }

/**
 * Consent dialog raised on IDE-B when IDE-A rings the doorbell ([DelegationMessage.Knock])
 * while inbound delegation is disabled for this project. The receiving human decides whether
 * to accept this one delegation, always accept from this delegator, or cancel.
 *
 * UI-only: it returns the user's [choice]; the socket/preauth/settings consequences are
 * applied by the caller (Plan 6 Task 5). Mirrors [AcceptDelegationDialog] construction style.
 *
 * Spec: docs/superpowers/specs/2026-05-22-cross-ide-agent-delegation-design.md §7 (Plan 6 doorbell).
 */
class DelegationInboundConsentDialog(
    project: Project,
    private val knock: DelegationMessage.Knock,
    // MODELESS: this dialog can pop unsolicited while IDE-B is already in use. An app-modal
    // popup would hijack the user's current work; modeless lets them finish and respond at will.
) : DialogWrapper(project, true, IdeModalityType.MODELESS) {

    var choice: ConsentChoice = ConsentChoice.CANCEL
        private set

    init {
        title = "Cross-IDE Delegation Request"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.border = JBUI.Borders.empty(8)
        panel.add(
            JBLabel(
                "<html><b>${knock.delegatorRepo}</b> wants to delegate a task to this project's Workflow agent:" +
                    "<br><br><i>\"${knock.requestPreview}\"</i><br><br>" +
                    "Inbound delegation is disabled for this project.</html>"
            ),
            BorderLayout.CENTER,
        )
        return panel
    }

    override fun createActions(): Array<Action> {
        val once = object : DialogWrapperAction("Allow once") {
            override fun doAction(e: ActionEvent) {
                choice = ConsentChoice.ALLOW_ONCE
                close(OK_EXIT_CODE)
            }
        }
        val always = object : DialogWrapperAction("Allow always") {
            override fun doAction(e: ActionEvent) {
                choice = ConsentChoice.ALLOW_ALWAYS
                close(OK_EXIT_CODE)
            }
        }
        // Cancel uses the default cancel action; choice stays CANCEL.
        return arrayOf(once, always, cancelAction)
    }
}

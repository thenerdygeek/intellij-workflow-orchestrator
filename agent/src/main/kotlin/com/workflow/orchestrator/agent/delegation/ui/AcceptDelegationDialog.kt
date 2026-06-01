package com.workflow.orchestrator.agent.delegation.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.workflow.orchestrator.core.delegation.DelegationMessage
import javax.swing.JComponent

/**
 * Modal dialog shown when this IDE receives a cross-IDE delegation request.
 * The receiving human user can Accept or Reject. Agent-B does not start
 * executing until Accept is clicked.
 *
 * Spec: docs/superpowers/specs/2026-05-22-cross-ide-agent-delegation-design.md §3.2 + §6.1.
 */
class AcceptDelegationDialog(
    project: Project,
    private val connect: DelegationMessage.Connect,
) : DialogWrapper(project) {

    init {
        title = "Incoming Delegation"
        setOKButtonText("Accept")
        setCancelButtonText("Reject")
        init()
    }

    private val requestArea = JBTextArea(8, 60).apply {
        text = connect.request
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
    }

    override fun createCenterPanel(): JComponent = panel {
        group("Incoming delegation from ${connect.delegatorRepo}") {
            row {
                // Show the delegator's REPO NAME — never the raw "ide-$pid" process
                // identifier, which is meaningless to the user.
                label("From repository: ${connect.delegatorRepo}")
            }
            row { label("Request:") }
            row {
                cell(JBScrollPane(requestArea))
                    .align(AlignX.FILL)
            }
            row {
                comment(
                    "Accepting starts a new agent session in this IDE running with your " +
                        "configured tool permissions. You can stop or take over at any time."
                )
            }
        }
    }
}

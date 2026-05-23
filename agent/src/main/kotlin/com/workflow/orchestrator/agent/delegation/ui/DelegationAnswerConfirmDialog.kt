package com.workflow.orchestrator.agent.delegation.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent
import javax.swing.JTextArea
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Modal dialog shown in IDE-A when [autoApproveDelegationAnswers] is false.
 *
 * The user sees the question raised by the delegated session alongside Agent-A's
 * proposed answer (editable). Clicking Send forwards the (possibly edited) answer;
 * clicking Cancel returns null to the caller so the tool can abort with a clear error.
 *
 * Spec: docs/superpowers/specs/2026-05-22-cross-ide-agent-delegation-design.md §3.3 + §6.3.
 */
class DelegationAnswerConfirmDialog(
    project: Project,
    private val question: String,
    private val proposedAnswer: String,
    private val repoName: String,
) : DialogWrapper(project) {

    /** The answer to send — may differ from [proposedAnswer] if the user edits the text area. */
    var editedAnswer: String = proposedAnswer
        private set

    init {
        title = "Confirm Answer to Delegated Session"
        setOKButtonText("Send")
        setCancelButtonText("Cancel")
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        group("Delegated session ($repoName) asks:") {
            row { label(question) }
        }
        group("Agent's proposed answer (edit before sending):") {
            row {
                val ta = JTextArea(proposedAnswer, 5, 60).apply {
                    lineWrap = true
                    wrapStyleWord = true
                    document.addDocumentListener(object : DocumentListener {
                        override fun insertUpdate(e: DocumentEvent?) { editedAnswer = text }
                        override fun removeUpdate(e: DocumentEvent?) { editedAnswer = text }
                        override fun changedUpdate(e: DocumentEvent?) { editedAnswer = text }
                    })
                }
                cell(ta)
            }
        }
    }
}

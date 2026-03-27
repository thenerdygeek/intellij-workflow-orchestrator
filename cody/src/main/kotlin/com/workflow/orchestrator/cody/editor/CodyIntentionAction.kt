package com.workflow.orchestrator.cody.editor

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.workflow.orchestrator.core.ai.LlmBrainFactory
import com.workflow.orchestrator.sonar.model.MappedIssue
import com.workflow.orchestrator.sonar.ui.SonarIssueAnnotator

class CodyIntentionAction : IntentionAction {

    override fun getText(): String = "Fix with AI Agent (Workflow)"

    override fun getFamilyName(): String = "Workflow Orchestrator"

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
        if (editor == null || file == null) return false
        if (!LlmBrainFactory.isAvailable()) return false
        val caretLine = editor.caretModel.logicalPosition.line
        val hasIssue = editor.markupModel.allHighlighters.any { hl ->
            val startLine = editor.document.getLineNumber(hl.startOffset)
            startLine == caretLine && hl.errorStripeTooltip != null
        }
        return hasIssue
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        if (editor == null || file == null) return
        val sonarIssue = findSonarIssueAtCaret(editor, editor.caretModel.offset) ?: return
        val filePath = file.virtualFile.path

        val prompt = buildString {
            appendLine("Fix the following SonarQube issue in this file.")
            appendLine()
            appendLine("**Issue:** [${sonarIssue.rule}] ${sonarIssue.message}")
            appendLine("**Type:** ${sonarIssue.type}")
            appendLine("**File:** ${file.virtualFile.name}")
            appendLine("**Lines:** ${sonarIssue.startLine}-${sonarIssue.endLine}")
            appendLine()
            appendLine("Read the file, understand the surrounding code, apply a minimal fix that resolves the issue without changing behavior, and verify with diagnostics that the fix compiles.")
        }

        com.workflow.orchestrator.core.ai.AgentChatRedirect.getInstance()?.sendToAgent(project, prompt, listOf(filePath))
    }

    private fun findSonarIssueAtCaret(editor: Editor, offset: Int): MappedIssue? {
        return editor.markupModel.allHighlighters
            .filter { it.startOffset <= offset && offset <= it.endOffset }
            .mapNotNull { it.getUserData(SonarIssueAnnotator.SONAR_ISSUE_KEY) }
            .firstOrNull()
    }

    override fun startInWriteAction(): Boolean = false
}

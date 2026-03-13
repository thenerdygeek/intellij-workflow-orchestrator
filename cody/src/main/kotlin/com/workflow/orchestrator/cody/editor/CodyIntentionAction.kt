package com.workflow.orchestrator.cody.editor

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.workflow.orchestrator.cody.protocol.Position
import com.workflow.orchestrator.cody.protocol.Range
import com.workflow.orchestrator.cody.service.CodyContextService
import com.workflow.orchestrator.cody.service.CodyEditService
import com.workflow.orchestrator.core.auth.CredentialStore
import com.workflow.orchestrator.core.model.ServiceType
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.sonar.model.MappedIssue
import com.workflow.orchestrator.sonar.ui.SonarIssueAnnotator
import kotlinx.coroutines.*

class CodyIntentionAction : IntentionAction {

    override fun getText(): String = "Workflow: Fix with Cody"

    override fun getFamilyName(): String = "Workflow Orchestrator"

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
        if (editor == null || file == null) return false
        val settings = PluginSettings.getInstance(project)
        if (settings.connections.sourcegraphUrl.isNullOrBlank()) return false
        if (settings.state.codyEnabled == false) return false
        if (!CredentialStore().hasToken(ServiceType.SOURCEGRAPH)) return false
        val caretLine = editor.caretModel.logicalPosition.line
        val hasIssue = editor.markupModel.allHighlighters.any { hl ->
            val startLine = editor.document.getLineNumber(hl.startOffset)
            startLine == caretLine && hl.errorStripeTooltip != null
        }
        return hasIssue
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        if (editor == null || file == null) return

        val caretOffset = editor.caretModel.offset
        val sonarIssue = findSonarIssueAtCaret(editor, caretOffset)

        val range = if (sonarIssue != null) {
            Range(
                start = Position(line = sonarIssue.startLine - 1, character = 0),
                end = Position(line = sonarIssue.endLine, character = 0)
            )
        } else {
            val caretLine = editor.caretModel.logicalPosition.line
            Range(
                start = Position(line = caretLine, character = 0),
                end = Position(line = caretLine + 1, character = 0)
            )
        }

        val filePath = file.virtualFile.path
        val contextService = project.service<CodyContextService>()

        @Suppress("DEPRECATION")
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val fixContext = contextService.gatherFixContext(
                filePath = filePath,
                issueRange = range,
                issueType = sonarIssue?.type?.name ?: "CODE_SMELL",
                issueMessage = sonarIssue?.message ?: "Fix issue at cursor",
                ruleKey = sonarIssue?.rule ?: "manual"
            )
            CodyEditService(project).requestFix(
                filePath = filePath,
                range = range,
                instruction = fixContext.instruction,
                contextFiles = fixContext.contextFiles
            )
        }
    }

    private fun findSonarIssueAtCaret(editor: Editor, offset: Int): MappedIssue? {
        return editor.markupModel.allHighlighters
            .filter { it.startOffset <= offset && offset <= it.endOffset }
            .mapNotNull { it.getUserData(SonarIssueAnnotator.SONAR_ISSUE_KEY) }
            .firstOrNull()
    }

    override fun startInWriteAction(): Boolean = false
}

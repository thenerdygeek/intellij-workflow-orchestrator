package com.workflow.orchestrator.cody.editor

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.runBackgroundableTask
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.workflow.orchestrator.cody.protocol.Position
import com.workflow.orchestrator.cody.protocol.Range
import com.workflow.orchestrator.cody.service.CodyContextService
import com.workflow.orchestrator.cody.service.CodyEditService
import com.workflow.orchestrator.core.auth.CredentialStore
import com.workflow.orchestrator.core.model.ServiceType
import com.workflow.orchestrator.core.settings.PluginSettings
import kotlinx.coroutines.*

class CodyIntentionAction : IntentionAction {

    override fun getText(): String = "Workflow: Fix with Cody"

    override fun getFamilyName(): String = "Workflow Orchestrator"

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
        if (editor == null || file == null) return false
        val settings = PluginSettings.getInstance(project)
        if (settings.state.sourcegraphUrl.isNullOrBlank()) return false
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

        val caretLine = editor.caretModel.logicalPosition.line
        val lineStart = Position(line = caretLine, character = 0)
        val lineEnd = Position(line = caretLine + 1, character = 0)
        val range = Range(start = lineStart, end = lineEnd)

        val filePath = file.virtualFile.url
        val contextService = project.getService(CodyContextService::class.java)

        // Launch on IO dispatcher — avoids blocking a pooled thread with runBlocking
        @Suppress("DEPRECATION")
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val fixContext = contextService.gatherFixContext(
                filePath = filePath,
                issueRange = range,
                issueType = "CODE_SMELL",
                issueMessage = "Issue detected on this line",
                ruleKey = "unknown"
            )
            CodyEditService(project).requestFix(
                filePath = filePath,
                range = range,
                instruction = fixContext.instruction,
                contextFiles = fixContext.contextFiles
            )
        }
    }

    override fun startInWriteAction(): Boolean = false
}

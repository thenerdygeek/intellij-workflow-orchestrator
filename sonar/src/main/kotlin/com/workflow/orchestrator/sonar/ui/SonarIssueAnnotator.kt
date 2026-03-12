package com.workflow.orchestrator.sonar.ui

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.workflow.orchestrator.sonar.model.IssueSeverity
import com.workflow.orchestrator.sonar.model.IssueType
import com.workflow.orchestrator.sonar.model.MappedIssue
import com.workflow.orchestrator.sonar.model.SonarState
import com.workflow.orchestrator.sonar.service.SonarDataService

data class SonarAnnotationInput(
    val filePath: String,
    val state: SonarState
)

data class SonarAnnotationResult(
    val issues: List<MappedIssue>
)

class SonarIssueAnnotator : ExternalAnnotator<SonarAnnotationInput, SonarAnnotationResult>() {

    override fun collectInformation(file: PsiFile, editor: Editor, hasErrors: Boolean): SonarAnnotationInput? {
        val project = file.project
        val virtualFile = file.virtualFile ?: return null

        val baseDir = project.basePath?.let {
            com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(it)
        }
        val relativePath = if (baseDir != null) {
            com.intellij.openapi.vfs.VfsUtilCore.getRelativePath(virtualFile, baseDir) ?: virtualFile.path
        } else {
            virtualFile.path
        }

        val state = try {
            SonarDataService.getInstance(project).stateFlow.value
        } catch (_: Exception) { return null }

        if (state.issues.none { it.filePath == relativePath }) return null

        return SonarAnnotationInput(relativePath, state)
    }

    override fun doAnnotate(collectedInfo: SonarAnnotationInput): SonarAnnotationResult {
        val fileIssues = collectedInfo.state.issues.filter { it.filePath == collectedInfo.filePath }
        return SonarAnnotationResult(fileIssues)
    }

    override fun apply(file: PsiFile, annotationResult: SonarAnnotationResult, holder: AnnotationHolder) {
        val doc = file.viewProvider.document ?: return
        val editor = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(file.project)
            .selectedTextEditor

        for (issue in annotationResult.issues) {
            val startLine = (issue.startLine - 1).coerceIn(0, doc.lineCount - 1)
            val endLine = (issue.endLine - 1).coerceIn(0, doc.lineCount - 1)

            val startOffset = doc.getLineStartOffset(startLine) + issue.startOffset
            val endOffset = if (issue.endOffset > 0) {
                doc.getLineStartOffset(endLine) + issue.endOffset
            } else {
                doc.getLineEndOffset(endLine)
            }

            val textRange = TextRange(
                startOffset.coerceIn(0, doc.textLength),
                endOffset.coerceIn(0, doc.textLength)
            )

            if (textRange.isEmpty) continue

            val severity = mapSeverity(issue.type, issue.severity)

            // PSI resolution for richer tooltips
            val element = file.findElementAt(textRange.startOffset)
            val containingMethod = com.intellij.psi.util.PsiTreeUtil.getParentOfType(
                element, com.intellij.psi.PsiMethod::class.java
            )
            val containingClass = com.intellij.psi.util.PsiTreeUtil.getParentOfType(
                element, com.intellij.psi.PsiClass::class.java
            )

            val tooltip = buildString {
                append("[${issue.rule}] ${issue.message}")
                issue.effort?.let { effort -> append(" (effort: $effort)") }
                if (containingMethod != null) {
                    append("\n\nIn method: ${containingMethod.name}()")
                }
                if (containingClass != null) {
                    val classAnnotations = containingClass.annotations
                        .mapNotNull { ann -> ann.qualifiedName?.substringAfterLast('.') }
                    if (classAnnotations.isNotEmpty()) {
                        append("\nClass: @${classAnnotations.joinToString(", @")} ${containingClass.name}")
                    }
                }
            }

            holder.newAnnotation(severity, tooltip)
                .range(textRange)
                .tooltip(tooltip)
                .create()

            // Store issue in highlighter for CodyIntentionAction to retrieve
            if (editor != null) {
                val highlighters = editor.markupModel.allHighlighters
                    .filter { it.startOffset == textRange.startOffset && it.endOffset == textRange.endOffset }
                for (hl in highlighters) {
                    hl.putUserData(SONAR_ISSUE_KEY, issue)
                }
            }
        }
    }

    companion object {
        val SONAR_ISSUE_KEY = com.intellij.openapi.util.Key.create<MappedIssue>("workflow.sonar.issue")

        fun mapSeverity(type: IssueType, severity: IssueSeverity): HighlightSeverity = when {
            (type == IssueType.BUG || type == IssueType.VULNERABILITY) &&
                (severity == IssueSeverity.BLOCKER || severity == IssueSeverity.CRITICAL) ->
                HighlightSeverity.ERROR
            type == IssueType.BUG || type == IssueType.VULNERABILITY ->
                HighlightSeverity.WARNING
            else -> HighlightSeverity.WEAK_WARNING
        }
    }
}

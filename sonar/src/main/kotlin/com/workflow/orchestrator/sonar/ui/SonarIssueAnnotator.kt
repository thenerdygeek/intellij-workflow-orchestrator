package com.workflow.orchestrator.sonar.ui

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.readAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiClass
import com.intellij.psi.util.PsiTreeUtil
import com.workflow.orchestrator.sonar.model.IssueSeverity
import com.workflow.orchestrator.sonar.model.IssueType
import com.workflow.orchestrator.sonar.model.MappedIssue
import com.workflow.orchestrator.sonar.model.SonarState
import com.workflow.orchestrator.sonar.service.SonarDataService
import kotlinx.coroutines.runBlocking

/**
 * Input collected on EDT (with read access) in [collectInformation].
 * Intentionally lightweight — no PSI tree walks here.
 * The VirtualFile + Project are passed through so [doAnnotate] can
 * open a fresh PsiFile under a background read action.
 */
data class SonarAnnotationInput(
    val filePath: String,
    val state: SonarState,
    val fileIssues: List<MappedIssue>,
    val virtualFile: com.intellij.openapi.vfs.VirtualFile,
    val project: com.intellij.openapi.project.Project,
)

/**
 * A single fully-resolved annotation ready to be applied in [apply].
 * All PSI work, offset computation, and tooltip building is done in [doAnnotate].
 */
data class ResolvedAnnotation(
    val issue: MappedIssue,
    val textRange: TextRange,
    val severity: HighlightSeverity,
    val tooltip: String
)

/**
 * Result of [doAnnotate] — contains pre-built annotations ready for the EDT.
 */
data class SonarAnnotationResult(
    val annotations: List<ResolvedAnnotation>
)

class SonarIssueAnnotator : ExternalAnnotator<SonarAnnotationInput, SonarAnnotationResult>() {

    /**
     * Runs on EDT with read access. Intentionally lightweight — only collects
     * metadata required to filter issues and locate the file for [doAnnotate].
     * No PSI tree walks here; those belong in [doAnnotate] under a background readAction.
     */
    override fun collectInformation(file: PsiFile, editor: Editor, hasErrors: Boolean): SonarAnnotationInput? {
        val project = file.project
        if (com.intellij.openapi.project.DumbService.isDumb(project)) return null
        if (!com.workflow.orchestrator.core.settings.PluginSettings.getInstance(project).state.sonarInlineAnnotationsEnabled) return null
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

        val fileIssues = state.activeIssues.filter { it.filePath == relativePath }
        if (fileIssues.isEmpty()) return null

        return SonarAnnotationInput(
            filePath = relativePath,
            state = state,
            fileIssues = fileIssues,
            virtualFile = virtualFile,
            project = project,
        )
    }

    /**
     * Runs OFF-EDT in a background thread.
     * All PSI tree walks and document offset computations happen here under
     * a readAction so we never touch PSI on the EDT.
     */
    override fun doAnnotate(collectedInfo: SonarAnnotationInput): SonarAnnotationResult {
        val annotations = runBlocking {
            readAction {
                val psiFile = PsiManager.getInstance(collectedInfo.project)
                    .findFile(collectedInfo.virtualFile)
                    ?: return@readAction emptyList()
                val doc = psiFile.viewProvider.document
                    ?: return@readAction emptyList()

                val lineCount = doc.lineCount
                val textLength = doc.textLength
                val result = mutableListOf<ResolvedAnnotation>()

                for (issue in collectedInfo.fileIssues) {
                    val startLine = (issue.startLine - 1).coerceIn(0, lineCount - 1)
                    val endLine = (issue.endLine - 1).coerceIn(0, lineCount - 1)

                    val startOffset = (doc.getLineStartOffset(startLine) + issue.startOffset)
                        .coerceIn(0, textLength)
                    val endOffset = if (issue.endOffset > 0) {
                        (doc.getLineStartOffset(endLine) + issue.endOffset).coerceIn(0, textLength)
                    } else {
                        doc.getLineEndOffset(endLine).coerceIn(0, textLength)
                    }
                    val textRange = TextRange(startOffset, endOffset)
                    if (textRange.isEmpty) continue

                    // PSI context for tooltip — safe here under readAction
                    val element = psiFile.findElementAt(startOffset)
                    val containingMethod = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java)
                    val containingClass = PsiTreeUtil.getParentOfType(element, PsiClass::class.java)
                    val classAnnotations = containingClass?.annotations
                        ?.mapNotNull { ann -> ann.qualifiedName?.substringAfterLast('.') }
                        ?: emptyList()

                    val tooltip = buildString {
                        append("[${issue.rule}] ${issue.message}")
                        issue.effort?.let { append(" (effort: $it)") }
                        if (containingMethod?.name != null) append("\n\nIn method: ${containingMethod.name}()")
                        if (containingClass?.name != null && classAnnotations.isNotEmpty()) {
                            append("\nClass: @${classAnnotations.joinToString(", @")} ${containingClass.name}")
                        }
                    }

                    result.add(ResolvedAnnotation(
                        issue = issue,
                        textRange = textRange,
                        severity = mapSeverity(issue.type, issue.severity),
                        tooltip = tooltip
                    ))
                }
                result
            }
        }
        return SonarAnnotationResult(annotations)
    }

    /**
     * Runs on EDT. Zero PSI or document access — all work was done in [doAnnotate].
     */
    override fun apply(file: PsiFile, annotationResult: SonarAnnotationResult, holder: AnnotationHolder) {
        for (annotation in annotationResult.annotations) {
            holder.newAnnotation(annotation.severity, annotation.tooltip)
                .range(annotation.textRange)
                .tooltip(annotation.tooltip)
                .create()
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

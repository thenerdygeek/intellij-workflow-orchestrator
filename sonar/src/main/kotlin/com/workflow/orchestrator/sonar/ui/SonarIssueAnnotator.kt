package com.workflow.orchestrator.sonar.ui

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiClass
import com.intellij.psi.util.PsiTreeUtil
import com.workflow.orchestrator.sonar.model.IssueSeverity
import com.workflow.orchestrator.sonar.model.IssueType
import com.workflow.orchestrator.sonar.model.MappedIssue
import com.workflow.orchestrator.sonar.model.SonarState
import com.workflow.orchestrator.sonar.service.SonarDataService

/**
 * Input collected on EDT (with read access) in [collectInformation].
 * Contains the file path, Sonar state, and pre-computed PSI context
 * for each issue so that no PSI walks are needed in [apply].
 */
data class SonarAnnotationInput(
    val filePath: String,
    val state: SonarState,
    val lineCount: Int,
    val textLength: Int,
    /**
     * Pre-computed PSI context for each issue, keyed by issue key.
     * Computed in [collectInformation] (read access, on EDT) so that
     * [doAnnotate] (off-EDT) can build tooltips and [apply] (on EDT)
     * only creates lightweight annotation holders.
     */
    val psiContext: Map<String, IssuePsiContext> = emptyMap()
)

/**
 * Pre-computed PSI context for a single issue.
 * Resolved in [SonarIssueAnnotator.collectInformation] to avoid PSI walks in [apply].
 */
data class IssuePsiContext(
    val methodName: String?,
    val className: String?,
    val classAnnotations: List<String>
)

/**
 * A single fully-resolved annotation ready to be applied in [apply].
 * All PSI work and offset computation is done before this is created.
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
     * Runs on EDT with read access.
     * Collects the Sonar state AND pre-computes PSI context (method/class names)
     * for each issue so that [apply] never needs to do PSI tree walks.
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

        val doc = file.viewProvider.document ?: return null

        // Pre-compute PSI context for each issue while we have read access.
        // This moves findElementAt + PsiTreeUtil.getParentOfType OFF the apply() EDT path.
        val psiContext = mutableMapOf<String, IssuePsiContext>()
        for (issue in fileIssues) {
            val startLine = (issue.startLine - 1).coerceIn(0, doc.lineCount - 1)
            val startOffset = (doc.getLineStartOffset(startLine) + issue.startOffset)
                .coerceIn(0, doc.textLength)

            val element = file.findElementAt(startOffset)
            val containingMethod = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java)
            val containingClass = PsiTreeUtil.getParentOfType(element, PsiClass::class.java)

            val classAnnotations = containingClass?.annotations
                ?.mapNotNull { ann -> ann.qualifiedName?.substringAfterLast('.') }
                ?: emptyList()

            psiContext[issue.key] = IssuePsiContext(
                methodName = containingMethod?.name,
                className = containingClass?.name,
                classAnnotations = classAnnotations
            )
        }

        return SonarAnnotationInput(
            filePath = relativePath,
            state = state,
            lineCount = doc.lineCount,
            textLength = doc.textLength,
            psiContext = psiContext
        )
    }

    /**
     * Runs OFF-EDT in a background thread.
     * Builds the fully resolved annotations (tooltips, text ranges, severities)
     * using the pre-computed PSI context from [collectInformation].
     */
    override fun doAnnotate(collectedInfo: SonarAnnotationInput): SonarAnnotationResult {
        val fileIssues = collectedInfo.state.activeIssues.filter { it.filePath == collectedInfo.filePath }
        val lineCount = collectedInfo.lineCount
        val textLength = collectedInfo.textLength
        val psiContext = collectedInfo.psiContext

        val annotations = mutableListOf<ResolvedAnnotation>()

        for (issue in fileIssues) {
            val startLine = (issue.startLine - 1).coerceIn(0, lineCount - 1)
            val endLine = (issue.endLine - 1).coerceIn(0, lineCount - 1)

            // We cannot call doc.getLineStartOffset here (off-EDT, no document access),
            // so we compute approximate offsets from the issue's own offset fields.
            // The actual TextRange will be re-verified in apply() using the document.
            val severity = mapSeverity(issue.type, issue.severity)

            val ctx = psiContext[issue.key]
            val tooltip = buildString {
                append("[${issue.rule}] ${issue.message}")
                issue.effort?.let { effort -> append(" (effort: $effort)") }
                if (ctx?.methodName != null) {
                    append("\n\nIn method: ${ctx.methodName}()")
                }
                if (ctx?.className != null && ctx.classAnnotations.isNotEmpty()) {
                    append("\nClass: @${ctx.classAnnotations.joinToString(", @")} ${ctx.className}")
                }
            }

            // Store lines for apply() to compute the actual TextRange from the document
            annotations.add(ResolvedAnnotation(
                issue = issue,
                textRange = TextRange.EMPTY_RANGE, // placeholder — computed in apply()
                severity = severity,
                tooltip = tooltip
            ))
        }

        return SonarAnnotationResult(annotations)
    }

    /**
     * Runs on EDT. Only creates annotation holders — all heavy PSI work was
     * done in [collectInformation] and tooltip building in [doAnnotate].
     */
    override fun apply(file: PsiFile, annotationResult: SonarAnnotationResult, holder: AnnotationHolder) {
        val doc = file.viewProvider.document ?: return

        for (annotation in annotationResult.annotations) {
            val issue = annotation.issue

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

            holder.newAnnotation(annotation.severity, annotation.tooltip)
                .range(textRange)
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

package com.workflow.orchestrator.sonar.inspection

import com.intellij.analysis.AnalysisScope
import com.intellij.codeInspection.*
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.PsiFile
import com.workflow.orchestrator.sonar.model.IssueSeverity
import com.workflow.orchestrator.sonar.model.MappedIssue
import com.workflow.orchestrator.sonar.service.SonarDataService

/**
 * Global inspection that surfaces SonarQube issues as IntelliJ inspection problems.
 *
 * Reads from [SonarDataService]'s cached state — issues are matched to files by
 * relative path. Severity is mapped from Sonar to IntelliJ highlight types:
 * BLOCKER/CRITICAL → ERROR, MAJOR → WARNING, MINOR/INFO → WEAK_WARNING.
 */
class SonarGlobalInspectionTool : GlobalSimpleInspectionTool() {

    private val log = Logger.getInstance(SonarGlobalInspectionTool::class.java)

    override fun getGroupDisplayName(): String = "Workflow Orchestrator"

    override fun getDisplayName(): String = "SonarQube Issues"

    override fun getShortName(): String = "SonarQubeIssues"

    override fun isEnabledByDefault(): Boolean = true

    override fun checkFile(
        file: PsiFile,
        manager: InspectionManager,
        problemsHolder: ProblemsHolder,
        globalContext: GlobalInspectionContext,
        problemDescriptionsProcessor: ProblemDescriptionsProcessor
    ) {
        val project = file.project
        val virtualFile = file.virtualFile ?: return

        val basePath = project.basePath ?: return
        val baseDir = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(basePath) ?: return
        val relativePath = VfsUtilCore.getRelativePath(virtualFile, baseDir) ?: return

        val state = try {
            SonarDataService.getInstance(project).stateFlow.value
        } catch (e: Exception) {
            log.debug("SonarDataService not available: ${e.message}")
            return
        }

        val fileIssues = state.issues.filter { it.filePath == relativePath }
        if (fileIssues.isEmpty()) return

        val document = file.viewProvider.document ?: return

        for (issue in fileIssues) {
            val element = resolveElement(file, document, issue)
            val highlightType = mapSeverityToHighlightType(issue.severity)
            val description = "[${issue.rule}] ${issue.message}"

            if (element != null) {
                problemsHolder.registerProblem(element, description, highlightType)
            } else {
                // Fall back to file-level problem if we can't resolve the element
                problemsHolder.registerProblem(file, description, highlightType)
            }
        }
    }

    private fun resolveElement(
        file: PsiFile,
        document: com.intellij.openapi.editor.Document,
        issue: MappedIssue
    ): com.intellij.psi.PsiElement? {
        val lineIndex = (issue.startLine - 1).coerceIn(0, document.lineCount - 1)
        val lineStart = document.getLineStartOffset(lineIndex)
        val offset = (lineStart + issue.startOffset).coerceIn(0, document.textLength)
        return file.findElementAt(offset)
    }

    companion object {
        fun mapSeverityToHighlightType(severity: IssueSeverity): ProblemHighlightType = when (severity) {
            IssueSeverity.BLOCKER, IssueSeverity.CRITICAL -> ProblemHighlightType.ERROR
            IssueSeverity.MAJOR -> ProblemHighlightType.WARNING
            IssueSeverity.MINOR, IssueSeverity.INFO -> ProblemHighlightType.WEAK_WARNING
        }
    }
}

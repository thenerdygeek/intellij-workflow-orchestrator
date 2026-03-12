package com.workflow.orchestrator.sonar.ui

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.workflow.orchestrator.sonar.model.LineCoverageStatus
import com.workflow.orchestrator.sonar.model.SonarState
import com.workflow.orchestrator.sonar.service.SonarDataService
import javax.swing.Icon

class CoverageLineMarkerProvider : LineMarkerProvider {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        if (element.parent !is PsiFile && element != element.parent?.firstChild) return null

        val project = element.project
        val file = element.containingFile?.virtualFile ?: return null

        val baseDir = project.basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) }
        val relativePath = if (baseDir != null) {
            VfsUtilCore.getRelativePath(file, baseDir) ?: file.path
        } else {
            file.path
        }

        val state = getSonarState(project)
        val fileCoverage = state.fileCoverage[relativePath] ?: return null

        val doc = element.containingFile?.viewProvider?.document ?: return null
        val lineNumber = doc.getLineNumber(element.textRange.startOffset) + 1
        val lineStatus = fileCoverage.lineStatuses[lineNumber] ?: return null

        // Spring-aware: highlight uncovered @RequestMapping endpoints more urgently
        val isEndpoint = if (lineStatus == LineCoverageStatus.UNCOVERED) {
            val containingMethod = com.intellij.psi.util.PsiTreeUtil.getParentOfType(
                element, com.intellij.psi.PsiMethod::class.java
            )
            containingMethod?.annotations?.any {
                it.qualifiedName in REQUEST_MAPPING_ANNOTATIONS
            } ?: false
        } else false

        val (icon, tooltip) = when {
            lineStatus == LineCoverageStatus.COVERED -> ICON_COVERED to "Line covered"
            lineStatus == LineCoverageStatus.UNCOVERED && isEndpoint ->
                ICON_ENDPOINT_UNCOVERED to "UNCOVERED REST endpoint — high priority"
            lineStatus == LineCoverageStatus.UNCOVERED -> ICON_UNCOVERED to "Line not covered"
            else -> ICON_PARTIAL to "Partially covered (some branches uncovered)"
        }

        return LineMarkerInfo(
            element,
            element.textRange,
            icon,
            { tooltip },
            null,
            GutterIconRenderer.Alignment.LEFT,
            { tooltip }
        )
    }

    private fun getSonarState(project: Project): SonarState {
        return try {
            SonarDataService.getInstance(project).stateFlow.value
        } catch (_: Exception) {
            SonarState.EMPTY
        }
    }

    companion object {
        private val ICON_COVERED: Icon = IconLoader.getIcon("/icons/coverage-covered.svg", CoverageLineMarkerProvider::class.java)
        private val ICON_UNCOVERED: Icon = IconLoader.getIcon("/icons/coverage-uncovered.svg", CoverageLineMarkerProvider::class.java)
        private val ICON_PARTIAL: Icon = IconLoader.getIcon("/icons/coverage-partial.svg", CoverageLineMarkerProvider::class.java)
        private val ICON_ENDPOINT_UNCOVERED: Icon = IconLoader.getIcon("/icons/coverage-endpoint-uncovered.svg", CoverageLineMarkerProvider::class.java)

        private val REQUEST_MAPPING_ANNOTATIONS = setOf(
            "org.springframework.web.bind.annotation.RequestMapping",
            "org.springframework.web.bind.annotation.GetMapping",
            "org.springframework.web.bind.annotation.PostMapping",
            "org.springframework.web.bind.annotation.PutMapping",
            "org.springframework.web.bind.annotation.DeleteMapping",
            "org.springframework.web.bind.annotation.PatchMapping"
        )
    }
}

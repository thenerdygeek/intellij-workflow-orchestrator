package com.workflow.orchestrator.sonar.ui

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
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
import com.workflow.orchestrator.sonar.service.SonarDataService
import java.util.concurrent.ConcurrentHashMap
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

        val service = getDataService(project) ?: return null

        // Check the line coverage cache first
        val lineStatuses = service.lineCoverageCache[relativePath]
        if (lineStatuses == null) {
            // Not yet fetched — trigger async fetch, then re-render when done
            if (pendingFetches.putIfAbsent(relativePath, true) == null) {
                val psiFile = element.containingFile
                service.fetchLineCoverageAsync(relativePath) {
                    try {
                        // Re-trigger gutter marker rendering on the EDT
                        if (psiFile.isValid && !project.isDisposed) {
                            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                                if (!project.isDisposed && psiFile.isValid) {
                                    DaemonCodeAnalyzer.getInstance(project).restart(psiFile)
                                }
                            }
                        }
                    } finally {
                        pendingFetches.remove(relativePath)
                    }
                }
            }
            return null
        }

        if (lineStatuses.isEmpty()) return null

        val doc = element.containingFile?.viewProvider?.document ?: return null
        val lineNumber = doc.getLineNumber(element.textRange.startOffset) + 1
        val lineStatus = lineStatuses[lineNumber] ?: return null

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

    private fun getDataService(project: Project): SonarDataService? {
        return try {
            SonarDataService.getInstance(project)
        } catch (_: Exception) {
            null
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

        /**
         * Tracks which files have an in-flight line coverage fetch to avoid duplicate requests.
         * Entries are removed when the fetch completes.
         */
        private val pendingFetches = ConcurrentHashMap<String, Boolean>()
    }
}

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
import com.workflow.orchestrator.sonar.service.SpringEndpointCacheService
import java.util.concurrent.ConcurrentHashMap
import javax.swing.Icon

class CoverageLineMarkerProvider : LineMarkerProvider {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        if (element.parent !is PsiFile && element != element.parent?.firstChild) return null

        val project = element.project
        val file = element.containingFile?.virtualFile ?: return null

        val baseDir = project.basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) }
        val relativePath = if (baseDir != null) {
            (VfsUtilCore.getRelativePath(file, baseDir) ?: file.path).replace('\\', '/')
        } else {
            file.path.replace('\\', '/')
        }

        val service = getDataService(project) ?: return null

        // Check the line coverage cache first
        val lineStatuses = service.lineCoverageCache[relativePath]
        if (lineStatuses == null) {
            // Not yet fetched — trigger async fetch, then re-render when done
            val projectPending = getProjectPendingFetches(project)
            if (projectPending.putIfAbsent(relativePath, true) == null) {
                val psiFile = element.containingFile
                service.fetchLineCoverageAsync(relativePath) {
                    try {
                        // Trigger async Spring endpoint detection now that coverage data is available
                        triggerEndpointCachePopulation(project, relativePath)

                        // Re-trigger gutter marker rendering on the EDT
                        // PSI validity checks require a read action when called from background threads
                        val isValid = com.intellij.openapi.application.ReadAction.compute<Boolean, RuntimeException> {
                            !project.isDisposed && psiFile.isValid
                        }
                        if (isValid) {
                            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                                if (!project.isDisposed && psiFile.isValid) {
                                    DaemonCodeAnalyzer.getInstance(project).restart(psiFile)
                                }
                            }
                        }
                    } finally {
                        projectPending.remove(relativePath)
                    }
                }
            }
            return null
        }

        if (lineStatuses.isEmpty()) return null

        val doc = element.containingFile?.viewProvider?.document ?: return null
        val lineNumber = doc.getLineNumber(element.textRange.startOffset) + 1
        val lineStatus = lineStatuses[lineNumber] ?: return null

        // Spring-aware: use pre-computed cache (populated asynchronously off-EDT).
        // If the cache is not yet populated, we show the regular uncovered icon
        // — no PSI tree walks on the EDT.
        val isEndpoint = if (lineStatus == LineCoverageStatus.UNCOVERED) {
            getEndpointCacheService(project)?.isEndpointLine(relativePath, lineNumber) ?: false
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

    /**
     * After coverage data arrives for a file, trigger async computation of which
     * uncovered lines are inside Spring endpoint methods. This populates the
     * [SpringEndpointCacheService] so subsequent marker renders can distinguish
     * endpoint lines without PSI walks on the EDT.
     */
    private fun triggerEndpointCachePopulation(project: Project, relativePath: String) {
        if (project.isDisposed) return

        val service = getDataService(project) ?: return
        val lineStatuses = service.lineCoverageCache[relativePath] ?: return

        val uncoveredLines = lineStatuses.entries
            .filter { it.value == LineCoverageStatus.UNCOVERED }
            .map { it.key }
            .toSet()

        if (uncoveredLines.isEmpty()) return

        getEndpointCacheService(project)?.computeEndpointLinesAsync(relativePath, uncoveredLines)
    }

    private fun getDataService(project: Project): SonarDataService? {
        return try {
            SonarDataService.getInstance(project)
        } catch (_: Exception) {
            null
        }
    }

    private fun getEndpointCacheService(project: Project): SpringEndpointCacheService? {
        return try {
            SpringEndpointCacheService.getInstance(project)
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private val ICON_COVERED: Icon = IconLoader.getIcon("/icons/coverage-covered.svg", CoverageLineMarkerProvider::class.java)
        private val ICON_UNCOVERED: Icon = IconLoader.getIcon("/icons/coverage-uncovered.svg", CoverageLineMarkerProvider::class.java)
        private val ICON_PARTIAL: Icon = IconLoader.getIcon("/icons/coverage-partial.svg", CoverageLineMarkerProvider::class.java)
        private val ICON_ENDPOINT_UNCOVERED: Icon = IconLoader.getIcon("/icons/coverage-endpoint-uncovered.svg", CoverageLineMarkerProvider::class.java)

        /**
         * Per-project map tracking which files have an in-flight line coverage fetch
         * to avoid duplicate requests. Scoped to projects so disposal is clean.
         */
        private val projectPendingFetches = ConcurrentHashMap<Int, ConcurrentHashMap<String, Boolean>>()

        /**
         * Get the pending fetches map for a specific project, creating it if needed.
         * Uses project hash code as key; entries are cleaned up via [clearProjectState].
         */
        private fun getProjectPendingFetches(project: Project): ConcurrentHashMap<String, Boolean> {
            return projectPendingFetches.getOrPut(System.identityHashCode(project)) {
                ConcurrentHashMap()
            }
        }

        /**
         * Clean up all state associated with a project when it closes.
         * Called from [SonarDataService.dispose] to prevent memory leaks.
         */
        fun clearProjectState(project: Project) {
            projectPendingFetches.remove(System.identityHashCode(project))
        }
    }
}

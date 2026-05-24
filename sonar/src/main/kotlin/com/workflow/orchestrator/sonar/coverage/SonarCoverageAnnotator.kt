package com.workflow.orchestrator.sonar.coverage

import com.intellij.coverage.BaseCoverageAnnotator
import com.intellij.coverage.CoverageDataManager
import com.intellij.coverage.CoverageSuitesBundle
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiFile
import com.workflow.orchestrator.sonar.service.SonarDataService
import com.workflow.orchestrator.sonar.util.SonarPathResolver

/**
 * Annotator that provides coverage summary strings for the Coverage
 * tool-window columns ("% lines", "% branches") by reading from the
 * current [SonarDataService] state.
 */
class SonarCoverageAnnotator(project: Project) : BaseCoverageAnnotator(project) {

    override fun getDirCoverageInformationString(
        directory: PsiDirectory,
        bundle: CoverageSuitesBundle,
        manager: CoverageDataManager
    ): String? {
        // Directory-level aggregation is not provided by SonarQube
        // at per-directory granularity in a single API call.
        return null
    }

    override fun getFileCoverageInformationString(
        psiFile: PsiFile,
        bundle: CoverageSuitesBundle,
        manager: CoverageDataManager
    ): String? {
        val vFile = psiFile.virtualFile ?: return null
        val sonarService = try {
            SonarDataService.getInstance(project)
        } catch (_: Exception) {
            return null
        }

        // SonarDataService keys fileCoverage by Sonar's repo-relative path (via CoverageMapper),
        // not by the absolute VirtualFile path. Convert before lookup to prevent a permanent miss.
        val relativePath = SonarPathResolver.computeRelativePath(
            filePath = vFile.path,
            vcsRootPath = project.basePath,
            projectBasePath = project.basePath,
        )
        val fileCoverage = sonarService.stateFlow.value.fileCoverage[relativePath]
            ?: return null

        val linePct = "%.1f".format(fileCoverage.lineCoverage)
        val branchPct = "%.1f".format(fileCoverage.branchCoverage)
        return "$linePct% lines, $branchPct% branches"
    }

    override fun createRenewRequest(
        bundle: CoverageSuitesBundle,
        manager: CoverageDataManager
    ): Runnable = Runnable {
        // SonarQube coverage is fetched externally; nothing to recompute
        // when the platform asks for a refresh — data is already loaded
        // into the suite's ProjectData.
    }

    companion object {
        fun getInstance(project: Project): SonarCoverageAnnotator =
            project.getService(SonarCoverageAnnotator::class.java)
    }
}

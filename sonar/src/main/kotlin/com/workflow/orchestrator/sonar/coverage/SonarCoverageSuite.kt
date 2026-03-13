package com.workflow.orchestrator.sonar.coverage

import com.intellij.coverage.BaseCoverageSuite
import com.intellij.coverage.CoverageDataManager
import com.intellij.coverage.CoverageEngine
import com.intellij.coverage.CoverageFileProvider
import com.intellij.coverage.CoverageRunner
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.rt.coverage.data.ClassData
import com.intellij.rt.coverage.data.LineData
import com.intellij.rt.coverage.data.ProjectData
import com.workflow.orchestrator.sonar.model.FileCoverageData
import com.workflow.orchestrator.sonar.model.LineCoverageStatus
import com.workflow.orchestrator.sonar.service.SonarDataService

/**
 * A [BaseCoverageSuite] that loads coverage from SonarQube instead of a
 * local execution trace. The suite reads the current [SonarDataService]
 * state and converts per-file/per-line coverage into IntelliJ's
 * [ProjectData] model so the platform paints gutter markers via the
 * standard Coverage tool-window infrastructure.
 */
class SonarCoverageSuite : BaseCoverageSuite {

    private val log = Logger.getInstance(SonarCoverageSuite::class.java)

    /** No-arg constructor required for XML deserialization by the platform. */
    constructor() : super()

    constructor(
        name: String,
        project: Project,
        runner: CoverageRunner,
        fileProvider: CoverageFileProvider,
        timestamp: Long
    ) : super(name, project, runner, fileProvider, timestamp)

    /**
     * Build [ProjectData] from the current SonarQube state.
     *
     * The conversion maps each [FileCoverageData] entry to a [ClassData]
     * keyed by the file's fully-qualified path (matching IntelliJ's
     * internal representation). Line-level hit/miss information is
     * translated to [LineData] instances.
     */
    fun loadProjectData(): ProjectData? {
        val project = project ?: return null
        val sonarService = try {
            SonarDataService.getInstance(project)
        } catch (e: Exception) {
            log.warn("SonarDataService not available", e)
            return null
        }

        val sonarState = sonarService.stateFlow.value
        if (sonarState.fileCoverage.isEmpty()) {
            log.info("No SonarQube coverage data available")
            return null
        }

        val projectData = ProjectData()
        for ((filePath, fileCoverage) in sonarState.fileCoverage) {
            addClassCoverage(projectData, filePath, fileCoverage)
        }

        setCoverageData(projectData)
        return projectData
    }

    private fun addClassCoverage(
        projectData: ProjectData,
        filePath: String,
        fileCoverage: FileCoverageData
    ) {
        // IntelliJ Coverage uses FQ class names; for source-level coverage
        // the file path serves as the key.
        val classData = projectData.getOrCreateClassData(filePath)

        if (fileCoverage.lineStatuses.isEmpty()) return

        val maxLine = fileCoverage.lineStatuses.keys.maxOrNull() ?: return
        // LineData array is 1-indexed (index 0 unused), size = maxLine + 1
        val lines = arrayOfNulls<LineData>(maxLine + 1)

        for ((lineNumber, status) in fileCoverage.lineStatuses) {
            if (lineNumber < 1 || lineNumber > maxLine) continue
            val lineData = LineData(lineNumber, null)
            lineData.hits = when (status) {
                LineCoverageStatus.COVERED -> 1
                LineCoverageStatus.PARTIAL -> 1
                LineCoverageStatus.UNCOVERED -> 0
            }
            lines[lineNumber] = lineData
        }

        classData.setLines(lines)
    }

    override fun getCoverageEngine(): CoverageEngine =
        CoverageEngine.EP_NAME.findExtensionOrFail(SonarCoverageEngine::class.java)

    override fun getCoverageData(coverageDataManager: CoverageDataManager?): ProjectData? {
        // If data was already loaded, return it; otherwise build from Sonar.
        return super.getCoverageData(coverageDataManager) ?: loadProjectData()
    }
}

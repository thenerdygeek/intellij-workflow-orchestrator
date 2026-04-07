package com.workflow.orchestrator.sonar.coverage

import com.intellij.coverage.CoverageEngine
import com.intellij.coverage.CoverageRunner
import com.intellij.coverage.CoverageSuite
import com.intellij.rt.coverage.data.ProjectData
import java.io.File

/**
 * CoverageRunner that bridges SonarQube coverage data into IntelliJ's
 * native coverage infrastructure. Unlike traditional runners that load
 * data from binary execution traces, this runner synthesises [ProjectData]
 * from the coverage map already held by [SonarDataService].
 *
 * The actual data loading happens in [SonarCoverageSuite.loadProjectData];
 * the runner merely advertises a virtual file extension and accepts only
 * the [SonarCoverageEngine].
 */
class SonarCoverageRunner : CoverageRunner() {

    override fun getId(): String = RUNNER_ID

    override fun getPresentableName(): String = "SonarQube"

    override fun getDataFileExtension(): String = "sonar"

    override fun acceptsCoverageEngine(engine: CoverageEngine): Boolean =
        engine is SonarCoverageEngine

    /**
     * Loading from a file is not applicable — SonarQube data comes from
     * the REST API, not from a local trace file. When the suite is opened
     * the data has already been populated via [SonarCoverageSuite.setCoverageData].
     */
    override fun loadCoverageData(sessionDataFile: File, baseCoverageSuite: CoverageSuite?): ProjectData? {
        // Data is injected programmatically, not read from disk.
        return (baseCoverageSuite as? SonarCoverageSuite)?.getCoverageData()
    }

    companion object {
        const val RUNNER_ID = "SonarQubeCoverage"
    }
}

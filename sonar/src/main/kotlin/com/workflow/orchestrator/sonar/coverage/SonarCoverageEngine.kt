package com.workflow.orchestrator.sonar.coverage

import com.intellij.coverage.*
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.coverage.CoverageEnabledConfiguration
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile

/**
 * [CoverageEngine] implementation that surfaces SonarQube coverage data
 * through IntelliJ's standard Coverage tool-window and gutter markers.
 *
 * Because SonarQube coverage is imported (not collected by running tests
 * locally), many run-configuration-oriented methods return sensible
 * no-op values. The engine is never "applicable" to run configurations —
 * suites are created programmatically when the user requests a coverage
 * import from the Quality tab.
 */
class SonarCoverageEngine : CoverageEngine() {

    private val log = Logger.getInstance(SonarCoverageEngine::class.java)

    // ------------------------------------------------------------------ //
    //  Identity
    // ------------------------------------------------------------------ //

    override fun getPresentableText(): String = "SonarQube Coverage"

    // ------------------------------------------------------------------ //
    //  Run-configuration integration (not applicable for imported data)
    // ------------------------------------------------------------------ //

    /**
     * SonarQube coverage is never collected via a run configuration.
     * Return false so the platform does not offer this engine for
     * local test runs.
     */
    override fun isApplicableTo(conf: RunConfigurationBase<*>): Boolean = false

    override fun createCoverageEnabledConfiguration(
        conf: RunConfigurationBase<*>
    ): CoverageEnabledConfiguration {
        // Should never be called because isApplicableTo returns false.
        throw UnsupportedOperationException(
            "SonarQube coverage does not support run-configuration-based collection"
        )
    }

    // ------------------------------------------------------------------ //
    //  Suite factories
    // ------------------------------------------------------------------ //

    @Suppress("DEPRECATION")
    @Deprecated("Delegated to by the platform; required for compatibility.")
    override fun createCoverageSuite(
        covRunner: CoverageRunner,
        name: String,
        coverageDataFileProvider: CoverageFileProvider,
        filters: Array<out String>?,
        lastCoverageTimeStamp: Long,
        suiteToMerge: String?,
        coverageByTestEnabled: Boolean,
        branchCoverage: Boolean,
        trackTestFolders: Boolean,
        project: Project
    ): CoverageSuite? {
        if (covRunner !is SonarCoverageRunner) return null
        return SonarCoverageSuite(name, project, covRunner, coverageDataFileProvider, lastCoverageTimeStamp)
    }

    @Suppress("DEPRECATION")
    @Deprecated("Delegated to by the platform; required for compatibility.")
    override fun createCoverageSuite(
        covRunner: CoverageRunner,
        name: String,
        coverageDataFileProvider: CoverageFileProvider,
        config: CoverageEnabledConfiguration
    ): CoverageSuite? {
        if (covRunner !is SonarCoverageRunner) return null
        val project = config.configuration.project
        return SonarCoverageSuite(name, project, covRunner, coverageDataFileProvider, System.currentTimeMillis())
    }

    override fun createEmptyCoverageSuite(covRunner: CoverageRunner): CoverageSuite? {
        if (covRunner !is SonarCoverageRunner) return null
        return SonarCoverageSuite()
    }

    // ------------------------------------------------------------------ //
    //  Annotator — provides summary strings for the Coverage view
    // ------------------------------------------------------------------ //

    override fun getCoverageAnnotator(project: Project): CoverageAnnotator =
        SonarCoverageAnnotator.getInstance(project)

    // ------------------------------------------------------------------ //
    //  Editor highlighting
    // ------------------------------------------------------------------ //

    override fun coverageEditorHighlightingApplicableTo(psiFile: PsiFile): Boolean {
        val name = psiFile.name
        return name.endsWith(".java") || name.endsWith(".kt") || name.endsWith(".kts")
    }

    override fun acceptedByFilters(psiFile: PsiFile, suite: CoverageSuitesBundle): Boolean = true

    // ------------------------------------------------------------------ //
    //  Qualified names — used to match class data keys to PSI files
    // ------------------------------------------------------------------ //

    override fun getQualifiedNames(sourceFile: PsiFile): Set<String> {
        val vFile = sourceFile.virtualFile ?: return emptySet()
        // Return the path that SonarCoverageSuite uses as class-data key.
        return setOf(vFile.path)
    }

    companion object {
        fun getInstance(): SonarCoverageEngine =
            EP_NAME.findExtensionOrFail(SonarCoverageEngine::class.java)
    }
}

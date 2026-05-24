package com.workflow.orchestrator.sonar.coverage

import com.workflow.orchestrator.sonar.util.SonarPathResolver
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Regression test for audit finding sonar:F-6.
 *
 * SonarCoverageAnnotator looked up fileCoverage by absolute VirtualFile path, but
 * SonarDataService keys by Sonar's repo-relative path (via CoverageMapper). Result was
 * a permanent null — the Coverage tool-window column was always blank.
 *
 * The fix converts vFile.path → relative path before lookup. This test exercises the
 * path-conversion step that gates the lookup in isolation (no IntelliJ infra required).
 */
class SonarCoverageAnnotatorLookupTest {

    // Simulate project base path
    private val projectRoot = "/home/dev/myproject"

    // Relative path that SonarDataService / CoverageMapper uses as the map key
    private val relativeKey = "src/main/kt/Foo.kt"

    // Absolute path that a VirtualFile would expose
    private val absolutePath = "$projectRoot/$relativeKey"

    @Test
    fun `relative path computed from absolute path matches fileCoverage key`() {
        // This is the lookup the annotator now performs
        val computed = SonarPathResolver.computeRelativePath(
            filePath = absolutePath,
            vcsRootPath = projectRoot,
            projectBasePath = projectRoot,
        )
        assertEquals(relativeKey, computed)
    }

    @Test
    fun `fileCoverage map lookup succeeds after path conversion`() {
        // fileCoverage map keyed by repo-relative path (as CoverageMapper produces)
        val lineCoverage = 0.85
        val branchCoverage = 0.72
        val fileCoverage = mapOf(
            relativeKey to com.workflow.orchestrator.sonar.model.FileCoverageData(
                filePath = relativeKey,
                lineCoverage = lineCoverage,
                branchCoverage = branchCoverage,
                uncoveredLines = 3,
                uncoveredConditions = 1,
                lineStatuses = emptyMap(),
                projectKey = "myproject"
            )
        )

        // Before fix: absolute path lookup returned null
        assertNull(fileCoverage[absolutePath], "absolute path lookup must return null (wrong key)")

        // After fix: relative path lookup returns the data
        val computed = SonarPathResolver.computeRelativePath(absolutePath, projectRoot, projectRoot)
        val found = fileCoverage[computed]
        assertEquals(lineCoverage, found?.lineCoverage)
        assertEquals(branchCoverage, found?.branchCoverage)
    }

    @Test
    fun `lookup returns null for file outside the project root`() {
        // Files genuinely outside the project should still return null gracefully
        val outsidePath = "/tmp/unrelated/Other.kt"
        val fileCoverage = mapOf(relativeKey to com.workflow.orchestrator.sonar.model.FileCoverageData(
            filePath = relativeKey, lineCoverage = 0.9, branchCoverage = 0.8,
            uncoveredLines = 0, uncoveredConditions = 0, lineStatuses = emptyMap(), projectKey = "p"
        ))

        val computed = SonarPathResolver.computeRelativePath(outsidePath, projectRoot, projectRoot)
        // computeRelativePath returns the original absolute path when it can't strip the prefix
        assertNull(fileCoverage[computed])
    }
}

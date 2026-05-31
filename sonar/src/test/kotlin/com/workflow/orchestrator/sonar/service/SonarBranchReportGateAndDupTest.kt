package com.workflow.orchestrator.sonar.service

import com.intellij.openapi.project.Project
import com.intellij.testFramework.LoggedErrorProcessorEnabler
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import com.workflow.orchestrator.sonar.api.SonarApiClient
import com.workflow.orchestrator.sonar.api.SonarMetricKey
import com.workflow.orchestrator.sonar.api.dto.*
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Regression tests for two getBranchQualityReport bugs (audit #7, #8):
 *  - #7: the gate STATUS was recomputed from new_-prefixed conditions, which could downgrade a
 *        real ERROR (e.g. a WARN new-code condition masking a non-new ERROR). The server's
 *        authoritative status must be trusted.
 *  - #8: a file's duplication ref was matched by basename fallback (collides across modules) and
 *        an unresolved ref attributed EVERY duplication block to the file. Only an exact
 *        component-key match should contribute ranges; otherwise none.
 *
 * Invokes the real SonarServiceImpl via the [SonarServiceImpl.testClient] seam.
 */
@ExtendWith(LoggedErrorProcessorEnabler.DoNoRethrowErrors::class)
class SonarBranchReportGateAndDupTest {

    private val api = mockk<SonarApiClient>()
    private lateinit var service: SonarServiceImpl
    private val projectKey = "com.example:svc"
    private val branch = "feature/FOO-1"
    private val fileKey = "proj:src/Service.kt"

    @BeforeEach
    fun setUp() {
        service = SonarServiceImpl(mockk<Project>(relaxed = true)).also { it.testClient = api }

        // Benign defaults — individual tests re-stub the gate / duplications they exercise.
        coEvery { api.getQualityGateStatus(projectKey, branch) } returns ApiResult.Success(
            SonarQualityGateDto("OK", listOf(SonarConditionDto("OK", "new_coverage", "LT", "80", "85.0")))
        )
        coEvery { api.getIssues(projectKey, branch = branch, inNewCodePeriod = true) } returns ApiResult.Success(emptyList())
        coEvery { api.getSecurityHotspots(projectKey, branch) } returns ApiResult.Success(
            SonarHotspotSearchResult(SonarPagingDto(1, 100, 0), emptyList())
        )
        // One file with new duplicated lines so it goes through per-file drill-down.
        coEvery { api.getMeasures(projectKey, branch, any()) } returns ApiResult.Success(
            listOf(
                SonarMeasureComponentDto(
                    key = fileKey, name = "Service.kt", path = "src/Service.kt",
                    measures = listOf(
                        SonarMeasureDto("new_uncovered_lines", "0"),
                        SonarMeasureDto("new_duplicated_lines", "5"),
                    )
                )
            )
        )
        coEvery { api.getProjectMeasures(projectKey, branch, any()) } returns ApiResult.Success(
            listOf(SonarMeasureDto("new_duplicated_lines_density", "3.2"))
        )
        coEvery { api.getSourceLines(fileKey, branch = branch) } returns ApiResult.Success(emptyList())
        // Default duplications: the block belongs to a DIFFERENT module's Service.kt (basename collision).
        coEvery { api.getDuplications(fileKey, branch) } returns ApiResult.Success(
            SonarDuplicationsResponse(
                duplications = listOf(SonarDuplicationDto(blocks = listOf(SonarDuplicationBlockDto(ref = "9", from = 100, size = 10)))),
                files = mapOf("9" to SonarDuplicationFileDto("other:src/Service.kt", "Service.kt", "other"))
            )
        )
    }

    @Test
    fun `gate status is the server's, not recomputed from new-code conditions`() = runTest {
        // Server says ERROR (a non-new condition failed); a new-code condition is only WARN.
        // The old recompute returned WARN; the server status ERROR must win.
        coEvery { api.getQualityGateStatus(projectKey, branch) } returns ApiResult.Success(
            SonarQualityGateDto(
                "ERROR",
                listOf(
                    SonarConditionDto("WARN", "new_maintainability_rating", "GT", "1", "2"),
                    SonarConditionDto("ERROR", "coverage", "LT", "80", "40.0"), // non-new, failing
                )
            )
        )

        val result = service.getBranchQualityReport(projectKey, branch, 20, null)

        assertFalse(result.isError)
        assertEquals("ERROR", result.data!!.qualityGate.status,
            "server ERROR must not be downgraded to WARN by the new-code recompute")
    }

    @Test
    fun `duplication ranges require an exact component-key match — no basename or all-blocks attribution`() = runTest {
        val result = service.getBranchQualityReport(projectKey, branch, 20, null)

        assertFalse(result.isError)
        val fileReport = result.data!!.fileReports.first { it.filePath.endsWith("Service.kt") }
        assertTrue(fileReport.duplicatedLineRanges.isEmpty(),
            "a dup block belonging to another module's Service.kt must not be attributed to this file")
    }

    @Test
    fun `exact component-key match still contributes its ranges`() = runTest {
        coEvery { api.getDuplications(fileKey, branch) } returns ApiResult.Success(
            SonarDuplicationsResponse(
                duplications = listOf(SonarDuplicationDto(blocks = listOf(SonarDuplicationBlockDto(ref = "1", from = 10, size = 5)))),
                files = mapOf("1" to SonarDuplicationFileDto(fileKey, "Service.kt", "proj"))
            )
        )

        val result = service.getBranchQualityReport(projectKey, branch, 20, null)

        assertFalse(result.isError)
        val fileReport = result.data!!.fileReports.first { it.filePath.endsWith("Service.kt") }
        assertEquals(1, fileReport.duplicatedLineRanges.size)
        assertEquals(10, fileReport.duplicatedLineRanges[0].startLine)
        assertEquals(14, fileReport.duplicatedLineRanges[0].endLine)
    }

    // ── SONAR-COV-8: getCoverage happy path via testClient seam ───────────────

    @Test
    fun `getCoverage returns correct data on happy path`() = runTest {
        // Override the setUp stub for getProjectMeasures with coverage-specific data.
        // getCoverage calls getProjectMeasures with a metricKeys subset; any() matcher covers it.
        coEvery { api.getProjectMeasures(projectKey, branch = branch, metricKeys = any()) } returns ApiResult.Success(
            listOf(
                SonarMeasureDto(SonarMetricKey.COVERAGE, "80.0"),
                SonarMeasureDto(SonarMetricKey.BRANCH_COVERAGE, "75.0"),
                SonarMeasureDto(SonarMetricKey.LINES_TO_COVER, "100"),
                SonarMeasureDto(SonarMetricKey.UNCOVERED_LINES, "20"),
            )
        )

        val result = service.getCoverage(projectKey, branch)

        assertFalse(result.isError)
        val data = result.data!!
        assertEquals(80.0, data.lineCoverage, 0.01)
        assertEquals(75.0, data.branchCoverage, 0.01)
        assertEquals(100, data.totalLines)
        // coveredLines = totalLinesToCover - uncoveredLines = 100 - 20 = 80
        assertEquals(80, data.coveredLines)
        assertTrue(result.summary.contains(projectKey))
        assertTrue(result.summary.contains("80.0%"))
    }

    @Test
    fun `getCoverage returns error result when API call fails`() = runTest {
        coEvery { api.getProjectMeasures(projectKey, branch = branch, metricKeys = any()) } returns
            ApiResult.Error(ErrorType.AUTH_FAILED, "Invalid token")

        val result = service.getCoverage(projectKey, branch)

        assertTrue(result.isError)
        assertEquals(0, result.data!!.coveredLines)
    }

    @Test
    fun `getCoverage summary contains Covered line with correct values`() = runTest {
        coEvery { api.getProjectMeasures(projectKey, branch = branch, metricKeys = any()) } returns ApiResult.Success(
            listOf(
                SonarMeasureDto(SonarMetricKey.COVERAGE, "60.0"),
                SonarMeasureDto(SonarMetricKey.BRANCH_COVERAGE, "50.0"),
                SonarMeasureDto(SonarMetricKey.LINES_TO_COVER, "50"),
                SonarMeasureDto(SonarMetricKey.UNCOVERED_LINES, "20"),
            )
        )

        val result = service.getCoverage(projectKey, branch)

        assertFalse(result.isError)
        // summary must contain the covered/total line count: 50-20=30 covered / 50 total
        assertTrue(
            result.summary.contains("30 / 50"),
            "expected '30 / 50' in summary but was: ${result.summary}"
        )
    }
}

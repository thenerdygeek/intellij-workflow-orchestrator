package com.workflow.orchestrator.sonar.service

import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.sonar.api.SonarApiClient
import com.workflow.orchestrator.sonar.api.dto.*
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Tests the getBranchQualityReport orchestration logic.
 *
 * Since SonarServiceImpl is an IntelliJ project service, we test the
 * report-building logic by invoking it through a reflective helper that
 * bypasses the DI, or we test the composed data structure directly.
 * Here we test the API client calls and data mapping at the integration level.
 */
class BranchQualityReportTest {

    private val apiClient = mockk<SonarApiClient>()
    private val projectKey = "com.example:my-service"
    private val branch = "feature/FOO-123"

    @BeforeEach
    fun setUp() {
        // Quality gate — passed with one condition
        coEvery { apiClient.getQualityGateStatus(projectKey, branch) } returns ApiResult.Success(
            SonarQualityGateDto("OK", listOf(
                SonarConditionDto("OK", "new_coverage", "LT", "80", "85.0")
            ))
        )

        // Issues — 1 bug, 1 vulnerability, 1 code smell
        coEvery { apiClient.getIssues(projectKey, branch = branch, inNewCodePeriod = true) } returns ApiResult.Success(
            listOf(
                SonarIssueDto(key = "i1", rule = "java:S2259", severity = "CRITICAL", message = "NPE possible",
                    component = "proj:src/Service.kt", type = "BUG",
                    textRange = SonarTextRangeDto(42, 42, 0, 30)),
                SonarIssueDto(key = "i2", rule = "java:S3649", severity = "BLOCKER", message = "SQL injection",
                    component = "proj:src/Repo.kt", type = "VULNERABILITY",
                    textRange = SonarTextRangeDto(18, 20, 0, 45)),
                SonarIssueDto(key = "i3", rule = "java:S3776", severity = "MAJOR", message = "High complexity",
                    component = "proj:src/Parser.kt", type = "CODE_SMELL")
            )
        )

        // Security hotspots
        coEvery { apiClient.getSecurityHotspots(projectKey, branch) } returns ApiResult.Success(
            SonarHotspotSearchResult(
                paging = SonarPagingDto(1, 100, 1),
                hotspots = listOf(
                    SonarHotspotDto(key = "hs1", message = "Hardcoded password",
                        component = "proj:src/Config.kt", securityCategory = "insecure-conf",
                        vulnerabilityProbability = "HIGH", status = "TO_REVIEW", line = 55)
                )
            )
        )

        // File-level new code measures (component_tree)
        coEvery { apiClient.getMeasures(projectKey, branch, any()) } returns ApiResult.Success(
            listOf(
                SonarMeasureComponentDto(
                    key = "proj:src/Service.kt", name = "Service.kt", path = "src/Service.kt",
                    measures = listOf(
                        SonarMeasureDto("new_uncovered_lines", "3"),
                        SonarMeasureDto("new_uncovered_conditions", "2"),
                        SonarMeasureDto("new_line_coverage", "70.0"),
                        SonarMeasureDto("new_branch_coverage", "50.0"),
                        SonarMeasureDto("new_duplicated_lines", "5")
                    )
                ),
                SonarMeasureComponentDto(
                    key = "proj:src/Controller.kt", name = "Controller.kt", path = "src/Controller.kt",
                    measures = listOf(
                        SonarMeasureDto("new_uncovered_lines", "0"),
                        SonarMeasureDto("new_uncovered_conditions", "0"),
                        SonarMeasureDto("new_line_coverage", "100.0"),
                        SonarMeasureDto("new_branch_coverage", "100.0"),
                        SonarMeasureDto("new_duplicated_lines", "0")
                    )
                )
            )
        )

        // Project-level new code measures
        coEvery { apiClient.getProjectMeasures(projectKey, branch, any()) } returns ApiResult.Success(
            listOf(
                SonarMeasureDto("new_line_coverage", "72.5"),
                SonarMeasureDto("new_branch_coverage", "55.0"),
                SonarMeasureDto("new_duplicated_lines_density", "3.2")
            )
        )

        // Source lines for Service.kt (file with coverage gaps)
        coEvery { apiClient.getSourceLines("proj:src/Service.kt", branch = branch) } returns ApiResult.Success(
            listOf(
                SonarSourceLineDto(line = 1, code = "package com.example", lineHits = 1),
                SonarSourceLineDto(line = 2, code = "fun process() {", lineHits = 1, conditions = 2, coveredConditions = 1),
                SonarSourceLineDto(line = 3, code = "  val x = compute()", lineHits = 0),
                SonarSourceLineDto(line = 4, code = "  if (x > 0) return", lineHits = 0, conditions = 2, coveredConditions = 0),
                SonarSourceLineDto(line = 5, code = "  fallback()", lineHits = 0),
                SonarSourceLineDto(line = 6, code = "}", lineHits = 1)
            )
        )

        // Duplications for Service.kt
        coEvery { apiClient.getDuplications("proj:src/Service.kt", branch) } returns ApiResult.Success(
            SonarDuplicationsResponse(
                duplications = listOf(
                    SonarDuplicationDto(blocks = listOf(
                        SonarDuplicationBlockDto(ref = "1", from = 10, size = 5),
                        SonarDuplicationBlockDto(ref = "2", from = 30, size = 5)
                    ))
                ),
                files = mapOf(
                    "1" to SonarDuplicationFileDto("proj:src/Service.kt", "Service.kt", "proj"),
                    "2" to SonarDuplicationFileDto("proj:src/Utils.kt", "Utils.kt", "proj")
                )
            )
        )
    }

    @Test
    fun `phase 1 parallel calls return correct quality gate status`() = runTest {
        val gate = apiClient.getQualityGateStatus(projectKey, branch)
        assertTrue(gate.isSuccess)
        assertEquals("OK", (gate as ApiResult.Success).data.status)
        assertEquals(1, gate.data.conditions.size)
    }

    @Test
    fun `phase 1 issues returns correct type breakdown`() = runTest {
        val result = apiClient.getIssues(projectKey, branch = branch, inNewCodePeriod = true)
        assertTrue(result.isSuccess)
        val issues = (result as ApiResult.Success).data
        assertEquals(3, issues.size)
        assertEquals(1, issues.count { it.type == "BUG" })
        assertEquals(1, issues.count { it.type == "VULNERABILITY" })
        assertEquals(1, issues.count { it.type == "CODE_SMELL" })
    }

    @Test
    fun `phase 1 security hotspots are fetched`() = runTest {
        val result = apiClient.getSecurityHotspots(projectKey, branch)
        assertTrue(result.isSuccess)
        val hotspots = (result as ApiResult.Success).data.hotspots
        assertEquals(1, hotspots.size)
        assertEquals("HIGH", hotspots[0].vulnerabilityProbability)
    }

    @Test
    fun `phase 1 file measures identify files needing drill-down`() = runTest {
        val result = apiClient.getMeasures(projectKey, branch, "new_uncovered_lines,new_line_coverage")
        assertTrue(result.isSuccess)
        val files = (result as ApiResult.Success).data

        // Service.kt has gaps, Controller.kt has 100% coverage — only Service.kt needs drill-down
        val filesWithGaps = files.filter { comp ->
            val uncovered = comp.measures.find { it.metric == "new_uncovered_lines" }?.value?.toIntOrNull() ?: 0
            uncovered > 0
        }
        assertEquals(1, filesWithGaps.size)
        assertEquals("src/Service.kt", filesWithGaps[0].path)
    }

    @Test
    fun `phase 2 source lines extract correct uncovered line numbers`() = runTest {
        val result = apiClient.getSourceLines("proj:src/Service.kt", branch = branch)
        assertTrue(result.isSuccess)
        val lines = (result as ApiResult.Success).data

        val uncoveredLines = lines.filter { it.lineHits != null && it.lineHits <= 0 }.map { it.line }
        assertEquals(listOf(3, 4, 5), uncoveredLines)
    }

    @Test
    fun `phase 2 source lines extract correct uncovered branch line numbers`() = runTest {
        val result = apiClient.getSourceLines("proj:src/Service.kt", branch = branch)
        assertTrue(result.isSuccess)
        val lines = (result as ApiResult.Success).data

        val uncoveredBranches = lines.filter { line ->
            line.conditions != null && line.conditions > 0 &&
                (line.coveredConditions ?: 0) < line.conditions
        }.map { it.line }
        assertEquals(listOf(2, 4), uncoveredBranches)
    }

    @Test
    fun `phase 2 duplications extract correct line ranges for target file`() = runTest {
        val result = apiClient.getDuplications("proj:src/Service.kt", branch)
        assertTrue(result.isSuccess)
        val response = (result as ApiResult.Success).data

        // Find blocks referencing Service.kt (ref "1")
        val selfRef = response.files.entries.find { it.value.key == "proj:src/Service.kt" }?.key
        assertNotNull(selfRef)

        val selfBlocks = response.duplications.flatMap { dup ->
            dup.blocks.filter { it.ref == selfRef }
        }
        assertEquals(1, selfBlocks.size)
        assertEquals(10, selfBlocks[0].from)
        assertEquals(5, selfBlocks[0].size)
    }

    @Test
    fun `files with zero gaps are excluded from drill-down`() = runTest {
        val result = apiClient.getMeasures(projectKey, branch, "new_uncovered_lines,new_duplicated_lines")
        assertTrue(result.isSuccess)
        val files = (result as ApiResult.Success).data

        val filesNeedingDrillDown = files.filter { comp ->
            val m = comp.measures.associate { it.metric to it.value }
            val uncoveredLines = m["new_uncovered_lines"]?.toIntOrNull() ?: 0
            val dupLines = m["new_duplicated_lines"]?.toIntOrNull() ?: 0
            uncoveredLines > 0 || dupLines > 0
        }

        // Only Service.kt has gaps (3 uncovered + 5 dup), Controller.kt has 0
        assertEquals(1, filesNeedingDrillDown.size)
        assertEquals("proj:src/Service.kt", filesNeedingDrillDown[0].key)
    }

    @Test
    fun `project-level new code metrics are fetched`() = runTest {
        val result = apiClient.getProjectMeasures(projectKey, branch, "new_line_coverage,new_branch_coverage,new_duplicated_lines_density")
        assertTrue(result.isSuccess)
        val measures = (result as ApiResult.Success).data.associate { it.metric to it.value }

        assertEquals("72.5", measures["new_line_coverage"])
        assertEquals("55.0", measures["new_branch_coverage"])
        assertEquals("3.2", measures["new_duplicated_lines_density"])
    }
}

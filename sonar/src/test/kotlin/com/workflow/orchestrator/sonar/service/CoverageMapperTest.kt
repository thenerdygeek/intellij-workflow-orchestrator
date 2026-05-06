package com.workflow.orchestrator.sonar.service

import com.workflow.orchestrator.sonar.api.dto.SonarMeasureComponentDto
import com.workflow.orchestrator.sonar.api.dto.SonarMeasureDto
import com.workflow.orchestrator.sonar.api.dto.SonarMeasurePeriodDto
import com.workflow.orchestrator.sonar.api.dto.SonarSourceLineDto
import com.workflow.orchestrator.sonar.model.LineCoverageStatus
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CoverageMapperTest {

    @Test
    fun `maps component measures to file coverage`() {
        val components = listOf(
            SonarMeasureComponentDto(
                key = "com.myapp:my-app:src/main/kotlin/UserService.kt",
                path = "src/main/kotlin/UserService.kt",
                measures = listOf(
                    SonarMeasureDto("line_coverage", "78.5"),
                    SonarMeasureDto("branch_coverage", "65.0"),
                    SonarMeasureDto("uncovered_lines", "12"),
                    SonarMeasureDto("uncovered_conditions", "3")
                )
            )
        )

        val result = CoverageMapper.mapMeasures(components, projectKey = "test-project")

        assertEquals(1, result.size)
        val file = result["src/main/kotlin/UserService.kt"]!!
        assertEquals(78.5, file.lineCoverage, 0.01)
        assertEquals(65.0, file.branchCoverage, 0.01)
        assertEquals(12, file.uncoveredLines)
        assertEquals(3, file.uncoveredConditions)
    }

    @Test
    fun `skips components without path`() {
        val components = listOf(
            SonarMeasureComponentDto(
                key = "com.myapp:my-app",
                path = null,
                measures = listOf(SonarMeasureDto("coverage", "80.0"))
            )
        )

        val result = CoverageMapper.mapMeasures(components, projectKey = "test-project")

        assertTrue(result.isEmpty())
    }

    @Test
    fun `handles missing metrics gracefully`() {
        val components = listOf(
            SonarMeasureComponentDto(
                key = "k",
                path = "src/File.kt",
                measures = listOf(SonarMeasureDto("line_coverage", "50.0"))
            )
        )

        val result = CoverageMapper.mapMeasures(components, projectKey = "test-project")

        val file = result["src/File.kt"]!!
        assertEquals(50.0, file.lineCoverage, 0.01)
        assertEquals(0.0, file.branchCoverage, 0.01)
    }

    @Test
    fun `maps source lines to line statuses`() {
        val lines = listOf(
            SonarSourceLineDto(line = 1, lineHits = 5),
            SonarSourceLineDto(line = 2, lineHits = 0),
            SonarSourceLineDto(line = 3, lineHits = 3, conditions = 2, coveredConditions = 1),
            SonarSourceLineDto(line = 4, lineHits = null)
        )

        val result = CoverageMapper.mapLineStatuses(lines)

        assertEquals(3, result.size)
        assertEquals(LineCoverageStatus.COVERED, result[1])
        assertEquals(LineCoverageStatus.UNCOVERED, result[2])
        assertEquals(LineCoverageStatus.PARTIAL, result[3])
        assertNull(result[4]) // non-executable lines excluded
    }

    @Test
    fun `maps new_coverage and new_branch_coverage when present`() {
        val components = listOf(
            SonarMeasureComponentDto(
                key = "com.myapp:my-app:src/main/kotlin/OrderService.kt",
                path = "src/main/kotlin/OrderService.kt",
                measures = listOf(
                    SonarMeasureDto("line_coverage", "80.0"),
                    SonarMeasureDto("branch_coverage", "70.0"),
                    SonarMeasureDto("uncovered_lines", "4"),
                    SonarMeasureDto("uncovered_conditions", "1"),
                    SonarMeasureDto("new_coverage", "100.0"),
                    SonarMeasureDto("new_branch_coverage", "95.5")
                )
            )
        )

        val result = CoverageMapper.mapMeasures(components, projectKey = "test-project")

        val file = result["src/main/kotlin/OrderService.kt"]!!
        assertEquals(100.0, file.newCoverage!!, 0.01)
        assertEquals(95.5, file.newBranchCoverage!!, 0.01)
    }

    @Test
    fun `new_coverage and new_branch_coverage are null when absent`() {
        val components = listOf(
            SonarMeasureComponentDto(
                key = "k",
                path = "src/File.kt",
                measures = listOf(SonarMeasureDto("line_coverage", "50.0"))
            )
        )

        val result = CoverageMapper.mapMeasures(components, projectKey = "test-project")

        val file = result["src/File.kt"]!!
        assertNull(file.newCoverage)
        assertNull(file.newBranchCoverage)
    }

    @Test
    fun `fully covered conditions maps to COVERED`() {
        val lines = listOf(
            SonarSourceLineDto(line = 1, lineHits = 2, conditions = 2, coveredConditions = 2)
        )

        val result = CoverageMapper.mapLineStatuses(lines)

        assertEquals(LineCoverageStatus.COVERED, result[1])
    }

    @Test
    fun `maps new_lines_to_cover and new_uncovered_lines and complexity fields`() {
        // Regression test: these four fields populated the four columns
        // ("New Uncov. Lines", "New Lines", "Complexity", "Cognitive") and the
        // new-code filter — they were always 0/null in production until the
        // sonarMetricKeys override was removed.
        val components = listOf(
            SonarMeasureComponentDto(
                key = "k",
                path = "src/PaymentService.kt",
                measures = listOf(
                    SonarMeasureDto("new_lines_to_cover", "42"),
                    SonarMeasureDto("new_uncovered_lines", "8"),
                    SonarMeasureDto("complexity", "27"),
                    SonarMeasureDto("cognitive_complexity", "19")
                )
            )
        )

        val file = CoverageMapper.mapMeasures(components, projectKey = "test")["src/PaymentService.kt"]!!

        assertEquals(42, file.newLinesToCover)
        assertEquals(8, file.newUncoveredLines)
        assertEquals(27, file.complexity)
        assertEquals(19, file.cognitiveComplexity)
    }

    @Test
    fun `maps new_ metrics from period value not top-level value (real SonarQube shape)`() {
        // Empirically captured 2026-05-06 from next.sonarqube.com:
        //   {"metric":"new_lines_to_cover","period":{"index":1,"value":"42"}}
        //   {"metric":"new_uncovered_lines","period":{"index":1,"value":"8","bestValue":true}}
        //   {"metric":"complexity","value":"27"}
        // SonarQube returns new_* metric values inside `period.value` when
        // additionalFields=period is in the request (which SonarApiClient.getMeasures
        // always includes when metricKeys contains "new_"). Reading the top-level
        // `value` field returns "" and the new-code filter drops every file silently.
        val components = listOf(
            SonarMeasureComponentDto(
                key = "k",
                path = "src/PaymentService.kt",
                measures = listOf(
                    SonarMeasureDto(metric = "new_lines_to_cover",
                        period = SonarMeasurePeriodDto(value = "42")),
                    SonarMeasureDto(metric = "new_uncovered_lines",
                        period = SonarMeasurePeriodDto(value = "8")),
                    SonarMeasureDto(metric = "new_coverage",
                        period = SonarMeasurePeriodDto(value = "78.5")),
                    SonarMeasureDto(metric = "new_branch_coverage",
                        period = SonarMeasurePeriodDto(value = "50.0")),
                    SonarMeasureDto(metric = "complexity", value = "27"),
                    SonarMeasureDto(metric = "cognitive_complexity", value = "19")
                )
            )
        )

        val file = CoverageMapper.mapMeasures(components, projectKey = "test")["src/PaymentService.kt"]!!

        assertEquals(42, file.newLinesToCover)
        assertEquals(8, file.newUncoveredLines)
        assertEquals(78.5, file.newCoverage!!, 0.01)
        assertEquals(50.0, file.newBranchCoverage!!, 0.01)
        assertEquals(27, file.complexity)
        assertEquals(19, file.cognitiveComplexity)
    }

    @Test
    fun `new-code filter keeps files with newLinesToCover gt 0 and drops the rest`() {
        // Mirrors SonarDataService.refreshWith line 349-350: the newCodeFileCoverage
        // map is built by filtering out any file whose new_lines_to_cover is null
        // or zero. Encodes the contract here so a future refactor can't silently
        // re-break the new-code Coverage tab.
        val components = listOf(
            SonarMeasureComponentDto(
                key = "k1",
                path = "src/Touched.kt",
                measures = listOf(SonarMeasureDto("new_lines_to_cover", "10"))
            ),
            SonarMeasureComponentDto(
                key = "k2",
                path = "src/Untouched.kt",
                measures = listOf(SonarMeasureDto("new_lines_to_cover", "0"))
            ),
            SonarMeasureComponentDto(
                key = "k3",
                path = "src/NoData.kt",
                measures = listOf(SonarMeasureDto("line_coverage", "75.0"))
            )
        )
        val fileCoverage = CoverageMapper.mapMeasures(components, projectKey = "test")

        val newCodeSubset = fileCoverage
            .filter { (_, data) -> data.newLinesToCover != null && data.newLinesToCover > 0 }

        assertEquals(setOf("src/Touched.kt"), newCodeSubset.keys)
    }
}

package com.workflow.orchestrator.sonar.service

import com.workflow.orchestrator.sonar.api.dto.SonarMeasureComponentDto
import com.workflow.orchestrator.sonar.api.dto.SonarMeasureDto
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

        val result = CoverageMapper.mapMeasures(components)

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

        val result = CoverageMapper.mapMeasures(components)

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

        val result = CoverageMapper.mapMeasures(components)

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
    fun `fully covered conditions maps to COVERED`() {
        val lines = listOf(
            SonarSourceLineDto(line = 1, lineHits = 2, conditions = 2, coveredConditions = 2)
        )

        val result = CoverageMapper.mapLineStatuses(lines)

        assertEquals(LineCoverageStatus.COVERED, result[1])
    }
}

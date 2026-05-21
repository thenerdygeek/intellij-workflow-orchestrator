package com.workflow.orchestrator.sonar.service

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Source-text contract for the conditional empty-new-code legend in
 * buildBranchQualityReportSummary. When a branch has zero new lines, the
 * quality gate reports new_coverage = 0.0 (ERROR) while the coverage
 * percentage metrics show 100% (vacuously true: 0 of 0 lines covered).
 * Both are correct; the legend explains the apparent contradiction.
 */
class SonarBranchReportLegendTest {

    @Test
    fun `branch_quality_report contains conditional empty-new-code legend`() {
        val candidates = listOf(
            java.io.File("src/main/kotlin/com/workflow/orchestrator/sonar/service/SonarServiceImpl.kt"),
            java.io.File("sonar/src/main/kotlin/com/workflow/orchestrator/sonar/service/SonarServiceImpl.kt"),
        )
        val sourceFile = candidates.firstOrNull { it.exists() }
            ?: error("SonarServiceImpl.kt not found in either ${candidates.joinToString()}")
        val source = sourceFile.readText()

        assertTrue(
            source.contains("empty-new-code") || source.contains("no new lines"),
            "Expected legend wording about empty-new-code state"
        )
        assertTrue(
            source.contains("new_coverage") && source.contains("≥ 80%"),
            "Expected legend to reference the gate-threshold semantic"
        )
        assertTrue(
            source.contains("new_line_coverage") && source.contains("100%"),
            "Expected legend to reference the vacuous-100% metric semantic"
        )
        assertTrue(
            source.contains("Both reflect the same state"),
            "Expected legend's reconciliation line"
        )
    }
}

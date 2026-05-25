package com.workflow.orchestrator.sonar.service

import com.workflow.orchestrator.sonar.api.SonarMetricKey
import com.workflow.orchestrator.sonar.api.dto.SonarMeasureComponentDto
import com.workflow.orchestrator.sonar.api.dto.SonarSourceLineDto
import com.workflow.orchestrator.sonar.model.FileCoverageData
import com.workflow.orchestrator.sonar.model.LineCoverageStatus

object CoverageMapper {

    fun mapMeasures(
        components: List<SonarMeasureComponentDto>,
        projectKey: String,
    ): Map<String, FileCoverageData> {
        return components
            .filter { it.path != null }
            .associate { comp ->
                val path = comp.path!!
                // SonarQube returns new_* metrics under measure.period.value (not the
                // top-level value) when additionalFields=period is in the request, which
                // SonarApiClient.getMeasures always includes for any new_* request. Read
                // through effectiveValue() so both shapes work.
                val measures = comp.measures.associate { it.metric to it.effectiveValue() }
                path to FileCoverageData(
                    filePath = path,
                    lineCoverage = measures[SonarMetricKey.LINE_COVERAGE]?.toDoubleOrNull() ?: 0.0,
                    branchCoverage = measures[SonarMetricKey.BRANCH_COVERAGE]?.toDoubleOrNull() ?: 0.0,
                    uncoveredLines = measures[SonarMetricKey.UNCOVERED_LINES]?.toIntOrNull() ?: 0,
                    uncoveredConditions = measures[SonarMetricKey.UNCOVERED_CONDITIONS]?.toIntOrNull() ?: 0,
                    lineStatuses = emptyMap(),
                    newCoverage = measures[SonarMetricKey.NEW_COVERAGE]?.toDoubleOrNull(),
                    newBranchCoverage = measures[SonarMetricKey.NEW_BRANCH_COVERAGE]?.toDoubleOrNull(),
                    newUncoveredLines = measures[SonarMetricKey.NEW_UNCOVERED_LINES]?.toIntOrNull(),
                    newLinesToCover = measures[SonarMetricKey.NEW_LINES_TO_COVER]?.toIntOrNull(),
                    complexity = measures[SonarMetricKey.COMPLEXITY]?.toIntOrNull() ?: 0,
                    cognitiveComplexity = measures[SonarMetricKey.COGNITIVE_COMPLEXITY]?.toIntOrNull() ?: 0,
                    projectKey = projectKey,
                )
            }
    }

    fun mapLineStatuses(lines: List<SonarSourceLineDto>): Map<Int, LineCoverageStatus> {
        return lines
            .filter { it.lineHits != null }
            .associate { line ->
                val status = when {
                    line.lineHits == 0 -> LineCoverageStatus.UNCOVERED
                    line.conditions != null && line.coveredConditions != null
                        && line.coveredConditions < line.conditions -> LineCoverageStatus.PARTIAL
                    else -> LineCoverageStatus.COVERED
                }
                line.line to status
            }
    }
}

package com.workflow.orchestrator.sonar.service

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
                    lineCoverage = measures["line_coverage"]?.toDoubleOrNull() ?: 0.0,
                    branchCoverage = measures["branch_coverage"]?.toDoubleOrNull() ?: 0.0,
                    uncoveredLines = measures["uncovered_lines"]?.toIntOrNull() ?: 0,
                    uncoveredConditions = measures["uncovered_conditions"]?.toIntOrNull() ?: 0,
                    lineStatuses = emptyMap(),
                    newCoverage = measures["new_coverage"]?.toDoubleOrNull(),
                    newBranchCoverage = measures["new_branch_coverage"]?.toDoubleOrNull(),
                    newUncoveredLines = measures["new_uncovered_lines"]?.toIntOrNull(),
                    newLinesToCover = measures["new_lines_to_cover"]?.toIntOrNull(),
                    complexity = measures["complexity"]?.toIntOrNull() ?: 0,
                    cognitiveComplexity = measures["cognitive_complexity"]?.toIntOrNull() ?: 0,
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

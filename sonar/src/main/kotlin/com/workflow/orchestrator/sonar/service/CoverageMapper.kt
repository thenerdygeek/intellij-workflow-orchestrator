package com.workflow.orchestrator.sonar.service

import com.workflow.orchestrator.sonar.api.dto.SonarMeasureComponentDto
import com.workflow.orchestrator.sonar.api.dto.SonarSourceLineDto
import com.workflow.orchestrator.sonar.model.FileCoverageData
import com.workflow.orchestrator.sonar.model.LineCoverageStatus

object CoverageMapper {

    fun mapMeasures(components: List<SonarMeasureComponentDto>): Map<String, FileCoverageData> {
        return components
            .filter { it.path != null }
            .associate { comp ->
                val measures = comp.measures.associate { it.metric to it.value }
                comp.path!! to FileCoverageData(
                    filePath = comp.path!!,
                    lineCoverage = measures["line_coverage"]?.toDoubleOrNull() ?: 0.0,
                    branchCoverage = measures["branch_coverage"]?.toDoubleOrNull() ?: 0.0,
                    uncoveredLines = measures["uncovered_lines"]?.toIntOrNull() ?: 0,
                    uncoveredConditions = measures["uncovered_conditions"]?.toIntOrNull() ?: 0,
                    lineStatuses = emptyMap(),
                    newCoverage = measures["new_coverage"]?.toDoubleOrNull(),
                    newBranchCoverage = measures["new_branch_coverage"]?.toDoubleOrNull()
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
                        && line.coveredConditions!! < line.conditions!! -> LineCoverageStatus.PARTIAL
                    else -> LineCoverageStatus.COVERED
                }
                line.line to status
            }
    }
}

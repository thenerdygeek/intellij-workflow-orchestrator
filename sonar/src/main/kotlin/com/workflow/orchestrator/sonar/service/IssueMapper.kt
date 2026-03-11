package com.workflow.orchestrator.sonar.service

import com.workflow.orchestrator.sonar.api.dto.SonarIssueDto
import com.workflow.orchestrator.sonar.model.IssueSeverity
import com.workflow.orchestrator.sonar.model.IssueType
import com.workflow.orchestrator.sonar.model.MappedIssue

object IssueMapper {

    fun mapIssues(dtos: List<SonarIssueDto>, projectKey: String): List<MappedIssue> {
        val prefix = "$projectKey:"
        return dtos.map { dto ->
            val filePath = if (dto.component.startsWith(prefix)) {
                dto.component.removePrefix(prefix)
            } else {
                dto.component
            }
            MappedIssue(
                key = dto.key,
                type = parseType(dto.type),
                severity = parseSeverity(dto.severity),
                message = dto.message,
                rule = dto.rule,
                filePath = filePath,
                startLine = dto.textRange?.startLine ?: 1,
                endLine = dto.textRange?.endLine ?: 1,
                startOffset = dto.textRange?.startOffset ?: 0,
                endOffset = dto.textRange?.endOffset ?: 0,
                effort = dto.effort
            )
        }
    }

    fun groupByFile(issues: List<MappedIssue>): Map<String, List<MappedIssue>> =
        issues.groupBy { it.filePath }

    private fun parseType(type: String): IssueType = when (type) {
        "BUG" -> IssueType.BUG
        "VULNERABILITY" -> IssueType.VULNERABILITY
        "CODE_SMELL" -> IssueType.CODE_SMELL
        "SECURITY_HOTSPOT" -> IssueType.SECURITY_HOTSPOT
        else -> IssueType.CODE_SMELL
    }

    private fun parseSeverity(severity: String): IssueSeverity = when (severity) {
        "BLOCKER" -> IssueSeverity.BLOCKER
        "CRITICAL" -> IssueSeverity.CRITICAL
        "MAJOR" -> IssueSeverity.MAJOR
        "MINOR" -> IssueSeverity.MINOR
        "INFO" -> IssueSeverity.INFO
        else -> IssueSeverity.INFO
    }
}

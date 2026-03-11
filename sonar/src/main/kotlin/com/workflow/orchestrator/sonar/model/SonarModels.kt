package com.workflow.orchestrator.sonar.model

enum class QualityGateStatus { PASSED, FAILED, NONE }

enum class IssueType { BUG, VULNERABILITY, CODE_SMELL, SECURITY_HOTSPOT }

enum class IssueSeverity { BLOCKER, CRITICAL, MAJOR, MINOR, INFO }

enum class LineCoverageStatus { COVERED, UNCOVERED, PARTIAL }

data class GateCondition(
    val metric: String,
    val comparator: String,
    val threshold: String,
    val actualValue: String,
    val passed: Boolean
)

data class QualityGateState(
    val status: QualityGateStatus,
    val conditions: List<GateCondition>
)

data class MappedIssue(
    val key: String,
    val type: IssueType,
    val severity: IssueSeverity,
    val message: String,
    val rule: String,
    val filePath: String,
    val startLine: Int,
    val endLine: Int,
    val startOffset: Int,
    val endOffset: Int,
    val effort: String?
)

data class FileCoverageData(
    val filePath: String,
    val lineCoverage: Double,
    val branchCoverage: Double,
    val uncoveredLines: Int,
    val uncoveredConditions: Int,
    val lineStatuses: Map<Int, LineCoverageStatus>
)

data class CoverageMetrics(
    val lineCoverage: Double,
    val branchCoverage: Double
)

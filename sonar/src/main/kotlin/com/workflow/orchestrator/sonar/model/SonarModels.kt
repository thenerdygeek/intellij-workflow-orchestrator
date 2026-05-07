package com.workflow.orchestrator.sonar.model

import com.workflow.orchestrator.core.ui.TimeFormatter

enum class QualityGateStatus { PASSED, FAILED, NONE }

enum class IssueType { BUG, VULNERABILITY, CODE_SMELL, SECURITY_HOTSPOT }

enum class IssueSeverity { BLOCKER, CRITICAL, MAJOR, MINOR, INFO }

enum class LineCoverageStatus { COVERED, UNCOVERED, PARTIAL }

data class GateCondition(
    val metric: String,
    val comparator: String,
    val threshold: String,
    val actualValue: String,
    val passed: Boolean,
    val warningThreshold: String? = null
)

data class QualityGateState(
    val status: QualityGateStatus,
    val conditions: List<GateCondition>,
    /**
     * Sonar 25.x "Clean as You Code" gate compliance — `compliant` /
     * `over-compliant` / `non-compliant`. Empty on pre-25.x servers.
     */
    val caycStatus: String = ""
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
    val effort: String?,
    val creationDate: String? = null,
    val status: String = "OPEN",
    /**
     * The Sonar project key the issue belongs to. Required for multi-repo projects:
     * `filePath` alone is repo-relative and cannot identify the owning repo when two
     * repos share a relative path. Carry the projectKey through so detail panels and
     * coverage lookups can resolve the owning [com.workflow.orchestrator.core.settings.RepoConfig]
     * (and its local VCS root + branch).
     */
    val projectKey: String,
    // Clean Code taxonomy (SonarQube 9.6+). All optional — older servers leave them empty.
    val cleanCodeAttribute: String? = null,
    val cleanCodeAttributeCategory: String? = null,
    val impacts: List<Impact> = emptyList(),
)

data class Impact(
    val softwareQuality: SoftwareQuality,
    val severity: ImpactSeverity,
)

enum class SoftwareQuality { RELIABILITY, SECURITY, MAINTAINABILITY, UNKNOWN }
enum class ImpactSeverity { INFO, LOW, MEDIUM, HIGH, BLOCKER, UNKNOWN }

data class FileCoverageData(
    val filePath: String,
    val lineCoverage: Double,
    val branchCoverage: Double,
    val uncoveredLines: Int,
    val uncoveredConditions: Int,
    val lineStatuses: Map<Int, LineCoverageStatus>,
    val newCoverage: Double? = null,
    val newBranchCoverage: Double? = null,
    val newUncoveredLines: Int? = null,
    val newLinesToCover: Int? = null,
    val complexity: Int = 0,
    val cognitiveComplexity: Int = 0,
    /**
     * The Sonar project key this file belongs to. Same multi-repo motivation as
     * [MappedIssue.projectKey]: `filePath` is repo-relative, so the projectKey is
     * needed to identify the owning [com.workflow.orchestrator.core.settings.RepoConfig].
     */
    val projectKey: String,
)

data class CoverageMetrics(
    val lineCoverage: Double,
    val branchCoverage: Double
)

data class IssueCounts(
    val bugs: Int = 0,
    val vulnerabilities: Int = 0,
    val codeSmells: Int = 0,
    val securityHotspots: Int = 0
)

data class SonarAnalysisTask(
    val id: String,
    val status: String,        // SUCCESS, FAILED, PENDING, IN_PROGRESS, CANCELED
    val branch: String?,
    val submittedAt: String?,
    val executedAt: String?,
    val executionTimeMs: Long?,
    val errorMessage: String?
)

data class NewCodePeriod(
    val type: String,          // REFERENCE_BRANCH, NUMBER_OF_DAYS, PREVIOUS_VERSION
    val value: String,         // branch name, number of days, or version
    val inherited: Boolean
)

data class ProjectHealthMetrics(
    val technicalDebtMinutes: Int = 0,
    val maintainabilityRating: String = "",  // A, B, C, D, E
    val reliabilityRating: String = "",
    val securityRating: String = "",
    val duplicatedLinesDensity: Double = 0.0,
    val cognitiveComplexity: Int = 0,
    val lineCoverage: Double? = null,
    val branchCoverage: Double? = null
) {
    /** Format technical debt as human-readable duration (e.g., "4h 30min", "2d 3h"). */
    val formattedDebt: String get() = TimeFormatter.formatEffortMinutes(technicalDebtMinutes)
}

data class SonarBranch(
    val name: String,
    val isMain: Boolean,
    val type: String,
    val qualityGateStatus: String?,
    val bugs: Int?,
    val vulnerabilities: Int?,
    val codeSmells: Int?,
    val analysisDate: String?
)

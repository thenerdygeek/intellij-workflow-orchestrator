package com.workflow.orchestrator.core.model.sonar

import kotlinx.serialization.Serializable

/**
 * Simplified SonarQube issue domain model shared between UI panels and AI agent.
 */
@Serializable
data class SonarIssueData(
    val key: String,
    val rule: String,
    val severity: String,       // "BLOCKER", "CRITICAL", "MAJOR", "MINOR", "INFO"
    val message: String,
    val component: String,
    val line: Int?,
    val status: String,
    val type: String            // "BUG", "VULNERABILITY", "CODE_SMELL"
) {
    override fun toString(): String {
        val file = component.substringAfterLast(':').substringAfterLast('/')
        val loc = if (line != null) "$file:$line" else file
        return "[$severity/$type] $loc — ${message.take(120)}"
    }
}

/**
 * Quality gate status with individual metric conditions.
 */
@Serializable
data class QualityGateData(
    val status: String,         // "OK", "ERROR"
    val conditions: List<QualityCondition> = emptyList()
) {
    override fun toString(): String = buildString {
        append("Quality Gate: $status")
        if (conditions.isNotEmpty()) {
            append("\n")
            conditions.forEach { c -> append("  ${c.metric}: ${c.value} (${c.status})\n") }
        }
    }.trimEnd()
}

/**
 * A single quality gate condition (metric threshold check).
 */
@Serializable
data class QualityCondition(
    val metric: String,
    val operator: String,
    val value: String,
    val status: String
)

/**
 * Code coverage metrics.
 */
@Serializable
data class CoverageData(
    val lineCoverage: Double,
    val branchCoverage: Double,
    val totalLines: Int,
    val coveredLines: Int
)

/**
 * Simplified SonarQube project domain model for project search/picker.
 */
@Serializable
data class SonarProjectData(
    val key: String,
    val name: String
)

/**
 * SonarQube Compute Engine analysis task data for build correlation.
 */
@Serializable
data class SonarAnalysisTaskData(
    val id: String,
    val status: String,        // SUCCESS, FAILED, PENDING, IN_PROGRESS, CANCELED
    val branch: String?,
    val errorMessage: String?,
    val executionTimeMs: Long?
)

/**
 * Project-level health metrics: technical debt, ratings, duplication.
 */
@Serializable
data class ProjectHealthData(
    val technicalDebtMinutes: Long,
    val technicalDebtFormatted: String,
    val maintainabilityRating: String,
    val reliabilityRating: String,
    val securityRating: String,
    val duplicatedLinesDensity: Double,
    val cognitiveComplexity: Long,
    val lineCoverage: Double,
    val branchCoverage: Double
)

/**
 * SonarQube branch info for a project.
 */
@Serializable
data class SonarBranchData(
    val name: String,
    val isMain: Boolean,
    val type: String,
    val qualityGateStatus: String?
)

/**
 * Project-level aggregate measures (ratings, coverage, debt).
 */
@Serializable
data class ProjectMeasuresData(
    val reliability: String?,
    val security: String?,
    val maintainability: String?,
    val coverage: Double?,
    val duplications: Double?,
    val technicalDebt: String?,
    val linesOfCode: Long?
)

/**
 * A single source line with coverage status.
 */
@Serializable
data class SourceLineData(
    val line: Int,
    val code: String,
    val coverageStatus: String?,
    val conditions: Int?,
    val coveredConditions: Int?
) {
    override fun toString(): String {
        val cov = coverageStatus?.let { " [$it]" } ?: ""
        return "${line.toString().padStart(4)}$cov ${code.take(120)}"
    }
}

/**
 * Security hotspot from SonarQube's dedicated hotspot API.
 */
@Serializable
data class SecurityHotspotData(
    val key: String,
    val message: String,
    val component: String,
    val line: Int?,
    val securityCategory: String,
    val probability: String,     // HIGH, MEDIUM, LOW
    val status: String,          // TO_REVIEW, REVIEWED
    val resolution: String?      // FIXED, SAFE, ACKNOWLEDGED
) {
    override fun toString(): String {
        val file = component.substringAfterLast(':').substringAfterLast('/')
        val loc = if (line != null) "$file:$line" else file
        return "[$probability] $loc — ${message.take(120)} ($status)"
    }
}

/**
 * Code duplication details showing which blocks are duplicated across files.
 */
@Serializable
data class DuplicationData(
    val blocks: List<DuplicationBlock>
) {
    override fun toString(): String = buildString {
        append("${blocks.size} duplication group(s)")
        blocks.forEachIndexed { i, block ->
            append("\n  Group ${i + 1}: ${block.fragments.joinToString(" ↔ ") { "${it.file}:${it.startLine}-${it.endLine}" }}")
        }
    }.trimEnd()
}

@Serializable
data class DuplicationBlock(
    val fragments: List<DuplicationFragment>
)

@Serializable
data class DuplicationFragment(
    val file: String,
    val startLine: Int,
    val endLine: Int
)

/**
 * Paged issue results with total count for pagination.
 */
@Serializable
data class PagedIssuesData(
    val issues: List<SonarIssueData>,
    val total: Int,
    val page: Int,
    val pageSize: Int
)

// ── Branch Quality Report (consolidated new-code report) ──────────────────

/**
 * A line range (inclusive) within a file.
 */
@Serializable
data class LineRange(val startLine: Int, val endLine: Int) {
    override fun toString(): String =
        if (startLine == endLine) "$startLine" else "$startLine-$endLine"
}

/**
 * Per-file quality details: exact uncovered lines, uncovered branches, and duplicated blocks.
 */
@Serializable
data class FileQualityReport(
    val filePath: String,
    val lineCoverage: Double?,
    val branchCoverage: Double?,
    val uncoveredLineNumbers: List<Int>,
    val uncoveredBranchLineNumbers: List<Int>,
    val duplicatedLineRanges: List<LineRange>
)

/**
 * Issue count breakdown by type.
 */
@Serializable
data class IssueSummary(
    val bugs: Int,
    val vulnerabilities: Int,
    val codeSmells: Int,
    val total: Int
)

/**
 * New-code coverage summary with uncovered counts.
 */
@Serializable
data class NewCodeCoverageSummary(
    val lineCoverage: Double?,
    val branchCoverage: Double?,
    val newUncoveredLines: Int,
    val newUncoveredConditions: Int,
    val duplicatedLinesDensity: Double?
)

/**
 * Consolidated branch quality report — one tool call gives the LLM everything
 * about new-code quality: quality gate, issues, hotspots, coverage gaps,
 * duplications, with exact line numbers per file.
 */
@Serializable
data class BranchQualityReportData(
    val branch: String,
    val qualityGate: QualityGateData,
    val issueSummary: IssueSummary,
    val issues: List<SonarIssueData>,
    val securityHotspots: List<SecurityHotspotData>,
    val coverageSummary: NewCodeCoverageSummary,
    val fileReports: List<FileQualityReport>,
    val truncatedFiles: Boolean = false
)

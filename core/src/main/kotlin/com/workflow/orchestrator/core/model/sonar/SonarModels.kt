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
)

/**
 * Quality gate status with individual metric conditions.
 */
@Serializable
data class QualityGateData(
    val status: String,         // "OK", "ERROR"
    val conditions: List<QualityCondition> = emptyList()
)

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
    val coverageStatus: String?,    // "covered", "uncovered", null (not coverable)
    val conditions: Int?,
    val coveredConditions: Int?
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

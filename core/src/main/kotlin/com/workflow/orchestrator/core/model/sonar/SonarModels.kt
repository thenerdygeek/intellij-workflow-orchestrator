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

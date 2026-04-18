package com.workflow.orchestrator.core.model.bamboo

import kotlinx.serialization.Serializable

/**
 * Simplified Bamboo build result domain model shared between UI panels and AI agent.
 */
@Serializable
data class BuildResultData(
    val planKey: String,
    val buildNumber: Int,
    val state: String,          // "Successful", "Failed", "Unknown"
    val durationSeconds: Long,
    val stages: List<BuildStageData> = emptyList(),
    val testsPassed: Int = 0,
    val testsFailed: Int = 0,
    val testsSkipped: Int = 0,
    val buildResultKey: String = "",
    val buildRelativeTime: String = ""
)

/**
 * A single stage within a Bamboo build.
 */
@Serializable
data class BuildStageData(
    val name: String,
    val state: String,
    val durationSeconds: Long
)

/**
 * Result of triggering a Bamboo build.
 */
@Serializable
data class BuildTriggerData(
    val buildKey: String,
    val buildNumber: Int,
    val link: String
)

/**
 * Test results summary for a Bamboo build/job.
 */
@Serializable
data class TestResultsData(
    val total: Int,
    val passed: Int,
    val failed: Int,
    val skipped: Int,
    val failedTests: List<FailedTestData> = emptyList()
)

/**
 * A single failed test case.
 */
@Serializable
data class FailedTestData(
    val className: String,
    val methodName: String,
    val message: String? = null
)

/**
 * A Bamboo plan variable (name-value pair).
 */
@Serializable
data class PlanVariableData(
    val name: String,
    val value: String
)

/**
 * A build artifact produced by a Bamboo job.
 */
@Serializable
data class ArtifactData(
    val name: String,
    val downloadUrl: String = "",
    val producerJobKey: String = "",
    val shared: Boolean = false,
    val size: Long = 0
)

/**
 * A Bamboo build plan.
 */
@Serializable
data class PlanData(
    val key: String,
    val name: String,
    val projectKey: String,
    val projectName: String,
    val enabled: Boolean = true
)

/**
 * A branch of a Bamboo build plan.
 */
@Serializable
data class PlanBranchData(
    val key: String,
    val name: String,
    val shortName: String = "",
    val enabled: Boolean = true
)

/**
 * A Bamboo project (contains plans).
 */
@Serializable
data class ProjectData(
    val key: String,
    val name: String,
    val description: String? = null
)

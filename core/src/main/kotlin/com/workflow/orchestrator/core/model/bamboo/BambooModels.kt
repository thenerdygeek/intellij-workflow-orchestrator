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
    val testsSkipped: Int = 0
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

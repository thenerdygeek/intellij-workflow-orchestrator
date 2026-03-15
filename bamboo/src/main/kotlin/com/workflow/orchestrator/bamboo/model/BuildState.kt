package com.workflow.orchestrator.bamboo.model

import java.time.Instant

enum class BuildStatus {
    SUCCESS, FAILED, IN_PROGRESS, PENDING, UNKNOWN;

    companion object {
        /** Maps Bamboo API state strings to BuildStatus. */
        fun fromBambooState(state: String, lifeCycleState: String): BuildStatus = when {
            lifeCycleState.equals("InProgress", ignoreCase = true) -> IN_PROGRESS
            lifeCycleState.equals("Queued", ignoreCase = true) -> PENDING
            lifeCycleState.equals("Pending", ignoreCase = true) -> PENDING
            state.equals("Successful", ignoreCase = true) -> SUCCESS
            state.equals("Failed", ignoreCase = true) -> FAILED
            else -> UNKNOWN
        }
    }
}

data class BuildState(
    val planKey: String,
    val branch: String,
    val buildNumber: Int,
    val stages: List<StageState>,
    val overallStatus: BuildStatus,
    val lastUpdated: Instant
)

data class StageState(
    val name: String,
    val status: BuildStatus,
    val manual: Boolean,
    val durationMs: Long?,
    val stageName: String = "",
    val resultKey: String = ""
)

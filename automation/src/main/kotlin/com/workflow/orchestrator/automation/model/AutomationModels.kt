package com.workflow.orchestrator.automation.model

import java.time.Instant

data class TagEntry(
    val serviceName: String,
    val currentTag: String,
    val latestReleaseTag: String?,
    val source: TagSource,
    val registryStatus: RegistryStatus,
    val isDrift: Boolean,
    val isCurrentRepo: Boolean
)

enum class TagSource { BASELINE, USER_EDIT, AUTO_DETECTED }

enum class RegistryStatus { VALID, NOT_FOUND, CHECKING, UNKNOWN, ERROR }

data class BaselineRun(
    val buildNumber: Int,
    val resultKey: String,
    val dockerTags: Map<String, String>,
    val releaseTagCount: Int,
    val totalServices: Int,
    val successfulStages: Int,
    val failedStages: Int,
    val triggeredAt: Instant,
    val score: Int
)

data class QueueEntry(
    val id: String,
    val suitePlanKey: String,
    val dockerTagsPayload: String,
    val variables: Map<String, String>,
    val stages: List<String>,
    val enqueuedAt: Instant,
    val status: QueueEntryStatus,
    val bambooResultKey: String?,
    val errorMessage: String? = null
)

enum class QueueEntryStatus {
    WAITING_LOCAL,
    TRIGGERING,
    QUEUED_ON_BAMBOO,
    RUNNING,
    COMPLETED,
    FAILED_TO_TRIGGER,
    // Kept for backwards-compat with pre-L3 persisted SQLite rows.
    // TagHistoryService.getActiveQueueEntries() deserialises via valueOf(); removing this
    // value would throw IllegalArgumentException on IDE startup for any user with a
    // persisted entry in this terminal state. No new entries are set to TAG_INVALID (L2 removed
    // the tag-validation flow). Safe to drop in a future release once a DB migration is added.
    @Deprecated("No new entries use this status. Retained only for safe deserialisation of pre-L3 SQLite rows.")
    TAG_INVALID,
    PLAN_UNAVAILABLE,
    STALE,
    CANCELLED
}

data class CurrentRepoContext(
    val serviceName: String,
    val branchName: String,
    val featureBranchTag: String?,
    val detectedFrom: DetectionSource
)

enum class DetectionSource { PROJECT_NAME, SETTINGS_MAPPING, GIT_BRANCH }

data class DriftResult(
    val serviceName: String,
    val currentTag: String,
    val latestReleaseTag: String,
    val isStale: Boolean
)

data class Conflict(
    val serviceName: String,
    val yourTag: String,
    val otherTag: String,
    val triggeredBy: String,
    val buildNumber: Int,
    val isRunning: Boolean
)

data class HistoryEntry(
    val id: String,
    val suitePlanKey: String,
    val dockerTagsJson: String,
    val variables: Map<String, String>,
    val stages: List<String>,
    val triggeredAt: Instant,
    val buildResultKey: String?,
    val buildPassed: Boolean?
)

/**
 * Diagnostic result from baseline loading — replaces opaque empty list.
 * Tells the UI exactly what happened and why.
 */
data class BaselineLoadResult(
    val tags: List<TagEntry>,
    val selectedBuild: BaselineRun?,
    val diagnostics: BaselineDiagnostics
)

data class BaselineDiagnostics(
    val buildsQueried: Int,
    val buildsWithVariables: Int,
    val buildsWithDockerTags: Int,
    val bambooError: String?,
    val skippedReasons: List<String>
) {
    /** Human-readable summary for the status label. */
    fun toStatusText(): String = when {
        bambooError != null -> "Bamboo error: $bambooError"
        buildsQueried == 0 -> "No recent builds found for this suite"
        buildsWithDockerTags == 0 && buildsWithVariables == 0 ->
            "Fetched $buildsQueried builds — none had build variables"
        buildsWithDockerTags == 0 ->
            "Fetched $buildsQueried builds ($buildsWithVariables had variables) — none had dockerTagsAsJson"
        else -> "" // success — caller formats the success message
    }
}

/**
 * Result from docker tag auto-detection for the current repo.
 */
data class TagDetectionResult(
    val detected: Boolean,
    val tag: String?,
    val buildKey: String?,
    val reason: String
) {
    companion object {
        fun success(tag: String, buildKey: String) = TagDetectionResult(
            detected = true, tag = tag, buildKey = buildKey,
            reason = "Detected from build $buildKey"
        )
        fun notConfigured(missing: String) = TagDetectionResult(
            detected = false, tag = null, buildKey = null,
            reason = "Not configured — set $missing in Settings"
        )
        fun noBuild(branch: String) = TagDetectionResult(
            detected = false, tag = null, buildKey = null,
            reason = "No CI build found for branch '$branch'"
        )
        fun noTagInLog(buildKey: String) = TagDetectionResult(
            detected = false, tag = null, buildKey = buildKey,
            reason = "Build $buildKey has no 'Unique Docker Tag' in log"
        )
        fun buildFailed(planKey: String, buildNumber: Int) = TagDetectionResult(
            detected = false, tag = null, buildKey = "$planKey-$buildNumber",
            reason = "CI build failed ($planKey #$buildNumber)"
        )
        fun logFetchFailed(resultKey: String) = TagDetectionResult(
            detected = false, tag = null, buildKey = resultKey,
            reason = "Could not fetch build log for $resultKey"
        )
        fun branchDetectionFailed() = TagDetectionResult(
            detected = false, tag = null, buildKey = null,
            reason = "Could not detect current git branch"
        )
    }
}

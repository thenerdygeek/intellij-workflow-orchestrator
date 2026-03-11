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

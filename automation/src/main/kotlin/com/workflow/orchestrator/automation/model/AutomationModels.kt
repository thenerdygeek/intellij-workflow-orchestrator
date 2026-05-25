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
    /**
     * Stage filter for this queue entry.
     *  - null  → run all stages (Bamboo default; also the backward-compatible value
     *    for entries persisted before the stage-picker feature was introduced).
     *  - non-null set → pass the first element as `stage=<name>` + `executeAllStages=false`
     *    to the Bamboo REST queue endpoint.  Empty set is rejected by [QueueService].
     */
    val stages: Set<String>?,
    val enqueuedAt: Instant,
    val status: QueueEntryStatus,
    val bambooResultKey: String?,
    val errorMessage: String? = null,
    /**
     * Optional Bamboo plan branch key to trigger instead of the suite master plan.
     *
     *  - null → trigger the suite master plan ([suitePlanKey]).
     *  - non-null → trigger the specified branch plan key directly (e.g. `PROJ-AUTOMATIONTEST336-3`).
     *    A Bamboo branch plan key is a valid `chainKey` and requires no additional resolution.
     *
     * The nullable default keeps all existing named-argument call sites and any pre-feature
     * persisted rows (schema v1) working without modification.
     */
    val branchKey: String? = null
)

enum class QueueEntryStatus {
    WAITING_LOCAL,
    TRIGGERING,
    QUEUED_ON_BAMBOO,
    RUNNING,
    COMPLETED,
    FAILED,
    FAILED_TO_TRIGGER,
    // Kept for backwards-compat with pre-L3 persisted SQLite rows.
    // TagHistoryService.getActiveQueueEntries() deserialises via valueOf(); removing this
    // value would throw IllegalArgumentException on IDE startup for any user with a
    // persisted entry in this terminal state. No new entries are set to TAG_INVALID (L2 removed
    // the tag-validation flow). Safe to drop in a future release once a DB migration is added.
    @Deprecated("No new entries use this status. Retained only for safe deserialisation of pre-L3 SQLite rows.")
    TAG_INVALID,
    CANCELLED;

    companion object {
        /**
         * Canonical set of terminal statuses — entries in any of these states are
         * done. Unlike pre-PR-8 behaviour, terminal entries are NOT removed from
         * [QueueService]'s `_stateFlow`; they remain visible in the Monitor list
         * until the user explicitly dismisses them via `QueueService.dismiss`.
         *
         * Single source of truth for terminal classification — referenced by
         * QueueService (for skip-on-poll), MonitorPanel (filter chips, Cancel
         * vs Remove button gating), and QueueStatusPanel.
         */
        @Suppress("DEPRECATION")
        val TERMINAL: Set<QueueEntryStatus> = setOf(
            COMPLETED,
            FAILED,
            CANCELLED,
            FAILED_TO_TRIGGER,
            // TAG_INVALID: deprecated in L3; kept so recovered pre-L3 SQLite rows
            // are treated as terminal rather than re-queued or shown as active.
            TAG_INVALID
        )
    }
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

/**
 * Diagnostic result from baseline loading — replaces opaque empty list.
 * Tells the UI exactly what happened and why.
 *
 * @property tags the [TagEntry] list rendered in the staging table — derived
 *   from `selectedBuild?.dockerTags` (or empty when no baseline was found).
 * @property selectedBuild the auto-picked baseline (top of the ranking) or
 *   null when no parseable build was found.
 * @property allRanked the full ranked list including [selectedBuild] at index 0
 *   (PR 7 #8). Powers the baseline picker dropdown — the user can switch the
 *   staged tags to any other parseable run by selecting from this list.
 *   Defaults to the single-element list `[selectedBuild]` when only one was
 *   parseable, or empty when none were.
 * @property diagnostics counters + skipped reasons surfaced in the banner.
 */
data class BaselineLoadResult(
    val tags: List<TagEntry>,
    val selectedBuild: BaselineRun?,
    val diagnostics: BaselineDiagnostics,
    val allRanked: List<BaselineRun> = listOfNotNull(selectedBuild)
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

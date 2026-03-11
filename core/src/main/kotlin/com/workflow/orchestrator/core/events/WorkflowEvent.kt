package com.workflow.orchestrator.core.events

/**
 * Sealed hierarchy for cross-module events dispatched through [EventBus].
 * Each phase adds its own subclasses. Only bamboo events exist in Phase 1C.
 */
sealed class WorkflowEvent {

    /** Emitted by :bamboo when a build reaches a terminal state. */
    data class BuildFinished(
        val planKey: String,
        val buildNumber: Int,
        val status: BuildEventStatus
    ) : WorkflowEvent()

    /** Emitted by :bamboo when a build log has been fetched and parsed. */
    data class BuildLogReady(
        val planKey: String,
        val buildNumber: Int,
        val log: String
    ) : WorkflowEvent()

    /** Emitted by :sonar when quality gate status changes. */
    data class QualityGateResult(
        val projectKey: String,
        val passed: Boolean
    ) : WorkflowEvent()

    /** Emitted by :sonar on each successful coverage data refresh. */
    data class CoverageUpdated(
        val projectKey: String,
        val lineCoverage: Double
    ) : WorkflowEvent()

    /** Emitted by :cody when a user accepts or rejects an AI-generated edit. */
    data class CodyEditReady(
        val taskId: String,
        val filePath: String,
        val accepted: Boolean
    ) : WorkflowEvent()

    /** Emitted when health checks start running (before commit). */
    data class HealthCheckStarted(
        val checks: List<String>
    ) : WorkflowEvent()

    /** Emitted when health checks finish. */
    data class HealthCheckFinished(
        val passed: Boolean,
        val results: Map<String, Boolean>,
        val durationMs: Long
    ) : WorkflowEvent()

    enum class BuildEventStatus { SUCCESS, FAILED }
}

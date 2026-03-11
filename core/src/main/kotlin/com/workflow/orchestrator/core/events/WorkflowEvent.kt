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

    enum class BuildEventStatus { SUCCESS, FAILED }
}

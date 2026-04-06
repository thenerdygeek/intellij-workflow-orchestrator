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

    /** Emitted by :automation when a build is triggered (manual or auto-queue). */
    data class AutomationTriggered(
        val suitePlanKey: String,
        val buildResultKey: String,
        val dockerTagsJson: String,
        val triggeredBy: String
    ) : WorkflowEvent()

    /** Emitted by :automation when a triggered build completes. */
    data class AutomationFinished(
        val suitePlanKey: String,
        val buildResultKey: String,
        val passed: Boolean,
        val durationMs: Long
    ) : WorkflowEvent()

    /** Emitted by :automation when queue position changes. */
    data class QueuePositionChanged(
        val suitePlanKey: String,
        val position: Int,
        val estimatedWaitMs: Long?
    ) : WorkflowEvent()

    /** Emitted by :handover when a PR is created via Bitbucket. */
    data class PullRequestCreated(
        val prUrl: String,
        val prNumber: Int,
        val ticketId: String
    ) : WorkflowEvent()

    /** Emitted by :handover when a Jira closure comment is posted. */
    data class JiraCommentPosted(
        val ticketId: String,
        val commentId: String
    ) : WorkflowEvent()

    /** Emitted by :jira when the active ticket changes (Start Work, branch switch). */
    data class TicketChanged(
        val ticketId: String,
        val ticketSummary: String
    ) : WorkflowEvent()

    /** Emitted when a pull request is merged. */
    data class PullRequestMerged(
        val prId: Int
    ) : WorkflowEvent()

    /** Emitted when a pull request is declined. */
    data class PullRequestDeclined(
        val prId: Int
    ) : WorkflowEvent()

    /** Emitted when a pull request is approved by a user. */
    data class PullRequestApproved(
        val prId: Int,
        val byUser: String
    ) : WorkflowEvent()

    /** Emitted by :jira when a ticket is detected from a branch but dismissed by the user. */
    data class TicketDetected(
        val ticketKey: String,
        val ticketSummary: String,
        val sprint: String?,
        val assignee: String?,
        val branchName: String? = null
    ) : WorkflowEvent()

    /** Emitted when the git branch changes (via BranchChangeListener). */
    data class BranchChanged(
        val branchName: String,
        val projectKey: String? = null,
        val repoSlug: String? = null
    ) : WorkflowEvent()

    /** Emitted when a PR is selected in the PR dashboard. */
    data class PrSelected(
        val prId: Int,
        val fromBranch: String,
        val toBranch: String,
        val repoName: String,
        val bambooPlanKey: String?,
        val sonarProjectKey: String?,
    ) : WorkflowEvent()

    /** Emitted by :jira Sprint tab when sprint tickets are loaded or refreshed.
     *  Consumed by :agent MentionSearchProvider to pre-populate # ticket autocomplete cache. */
    data class SprintDataLoaded(
        val tickets: List<com.workflow.orchestrator.core.model.jira.JiraTicketData>
    ) : WorkflowEvent()

    enum class BuildEventStatus { SUCCESS, FAILED }
}

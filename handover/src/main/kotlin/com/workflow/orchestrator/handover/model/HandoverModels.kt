package com.workflow.orchestrator.handover.model

import com.workflow.orchestrator.core.events.WorkflowEvent
import java.time.Instant

data class HandoverState(
    val ticketId: String = "",
    val ticketSummary: String = "",
    val currentBranch: String = "",

    // PR status
    val prUrl: String? = null,
    val prCreated: Boolean = false,

    // Build status (from BuildFinished events)
    val buildStatus: BuildSummary? = null,

    // Quality gate (from QualityGateResult events)
    val qualityGatePassed: Boolean? = null,

    // Health check (from HealthCheckFinished events)
    val healthCheckPassed: Boolean? = null,

    // Automation suites (accumulated from AutomationTriggered + AutomationFinished)
    val suiteResults: List<SuiteResult> = emptyList(),

    // Action completion tracking
    val copyrightFixed: Boolean = false,
    val jiraCommentPosted: Boolean = false,
    val jiraTransitioned: Boolean = false,
    val todayWorkLogged: Boolean = false,

    // Start work timestamp (from PluginSettings)
    val startWorkTimestamp: Long = 0L
)

data class SuiteResult(
    val suitePlanKey: String,
    val buildResultKey: String,
    val dockerTagsJson: String,
    val passed: Boolean?,
    val durationMs: Long?,
    val triggeredAt: Instant,
    val bambooLink: String
)

data class BuildSummary(
    val buildNumber: Int,
    val status: WorkflowEvent.BuildEventStatus,
    val planKey: String
)

// Copyright models
data class CopyrightFileEntry(
    val filePath: String,
    val status: CopyrightStatus,
    val oldYear: String? = null,
    val newYear: String? = null
)

enum class CopyrightStatus {
    OK,
    YEAR_OUTDATED,
    MISSING_HEADER
}

// Cody pre-review models
data class ReviewFinding(
    val severity: FindingSeverity,
    val filePath: String,
    val lineNumber: Int,
    val message: String,
    val pattern: String
)

enum class FindingSeverity { HIGH, MEDIUM, LOW }

// Macro models
data class MacroStep(
    val id: String,
    val label: String,
    val enabled: Boolean = true,
    val status: MacroStepStatus = MacroStepStatus.PENDING
)

enum class MacroStepStatus { PENDING, RUNNING, SUCCESS, FAILED, SKIPPED }

// Clipboard payload
data class ClipboardPayload(
    val dockerTags: Map<String, String>,
    val suiteLinks: List<SuiteLinkEntry>,
    val ticketIds: List<String>
)

data class SuiteLinkEntry(
    val suiteName: String,
    val passed: Boolean,
    val link: String
)

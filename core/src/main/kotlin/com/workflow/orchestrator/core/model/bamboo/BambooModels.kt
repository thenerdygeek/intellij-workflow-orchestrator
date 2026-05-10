package com.workflow.orchestrator.core.model.bamboo

import kotlinx.serialization.Serializable

/**
 * Simplified Bamboo build result domain model shared between UI panels and AI agent.
 */
@Serializable
data class BuildResultData(
    val planKey: String,
    val buildNumber: Int,
    /** Collapsed Bamboo state: "Successful", "Failed", or "Unknown" (when state is blank, lifeCycleState is used). */
    val state: String,
    val durationSeconds: Long,
    val stages: List<BuildStageData> = emptyList(),
    val testsPassed: Int = 0,
    val testsFailed: Int = 0,
    val testsSkipped: Int = 0,
    val buildResultKey: String = "",
    val buildRelativeTime: String = "",
    /**
     * Raw Bamboo lifeCycleState: "Finished", "NotBuilt", "InProgress", "Queued", "Pending".
     * Preserved alongside [state] so consumers can use the richer lifecycle signal for
     * terminal detection (e.g. "NotBuilt" is terminal; "InProgress" is not) without
     * relying on the lossy `state.ifBlank{lifeCycleState}` collapse.
     * Populated by BambooServiceImpl.mapBuildResult and sibling mappers (A-P1-1).
     */
    val lifeCycleState: String = "",
    /**
     * Bamboo build-level variables, populated when the result was fetched via the
     * result-collection endpoint (GET /result/{planKey}?expand=variables.variable).
     * The endpoint already expands variables inline, so [BambooServiceImpl.getRecentBuilds]
     * carries them through here to avoid per-build re-fetch calls in downstream consumers
     * such as TagBuilderService.scoreAndRankRuns.
     * Defaults to empty map for callers that construct BuildResultData from other endpoints
     * which do not expand variables.
     */
    val variables: Map<String, String> = emptyMap()
)

/**
 * A single stage within a Bamboo build. Carries its constituent jobs when the build
 * was fetched with `?expand=stages.stage.results.result` (default for live fetches
 * across `BambooApiClient.getLatestResult`, `getBuildResult`, and `getRecentResults`).
 *
 * `jobs` defaults to an empty list to preserve source-compatibility for any out-of-tree
 * caller that constructs `BuildStageData` by name. In production the field is populated
 * from `BambooStageDto.results.result[]` via `BambooServiceImpl.toBuildStageData()`.
 */
@Serializable
data class BuildStageData(
    val name: String,
    val state: String,
    val durationSeconds: Long,
    val jobs: List<BuildJobData> = emptyList()
)

/**
 * A single job (Bamboo's REST vocabulary calls these "results") within a stage.
 *
 * `resultKey` is the build-result key of the job — e.g. `PROJ-PLAN138-COMPILE-4`.
 * It is what the agent should pass to `bamboo_builds.get_build_log` to fetch just
 * this job's log, or to `bamboo_builds.get_test_results` for just this job's tests.
 * Bamboo's `/download/{key}/build_logs/{key}.log` and `/result/{key}?expand=...`
 * endpoints accept build-level OR job-level keys uniformly.
 */
@Serializable
data class BuildJobData(
    val name: String,
    val state: String,
    val durationSeconds: Long,
    val resultKey: String
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
 *
 * Plan-level variables (returned by `?expand=variableContext` on a plan) carry
 * three additional fields that build-level variables don't:
 *  - [isPassword] — Bamboo flags secret variables here. UI must render these
 *    with a masking field (`JBPasswordField`) and never log the value.
 *  - [variableType] — `PLAN`, `GLOBAL`, or `PARENT`. Useful for grouping in the
 *    variable picker; not surfaced in the UI today.
 *  - [description] — administrator note shown next to the variable in Bamboo's
 *    plan-config UI. Optional.
 *
 * Build-level variables (from `?expand=variables` on a result) populate only
 * `name` + `value` and leave the secret fields at their defaults — that's safe
 * because Bamboo redacts password variable values out of the result-level
 * response anyway, so the UI never receives them in clear text from this path.
 */
@Serializable
data class PlanVariableData(
    val name: String,
    val value: String,
    val isPassword: Boolean = false,
    val variableType: String = "",
    val description: String = ""
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
    val shortName: String = "",
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

/**
 * A single commit entry from a build's change list (R-ADD-1, §8.8).
 * Sourced from GET /result/{key}?expand=changes.change, validated on Bamboo 10.2.14.
 * bundle-repo.unpacked/raw/result_changes.json is the canonical shape reference.
 */
@Serializable
data class BuildChangeData(
    val userName: String,
    val fullName: String,
    val comment: String,
    val changesetId: String,
    val commitUrl: String,
    val date: String
)

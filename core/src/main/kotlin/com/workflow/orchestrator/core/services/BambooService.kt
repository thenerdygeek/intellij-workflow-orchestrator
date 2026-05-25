package com.workflow.orchestrator.core.services

import com.workflow.orchestrator.core.model.bamboo.BuildChangeData
import com.workflow.orchestrator.core.model.bamboo.BuildResultData
import com.workflow.orchestrator.core.model.bamboo.BuildTriggerData
import com.workflow.orchestrator.core.model.bamboo.PlanBranchData
import com.workflow.orchestrator.core.model.bamboo.PlanData
import com.workflow.orchestrator.core.model.bamboo.PlanVariableData
import com.workflow.orchestrator.core.model.bamboo.ProjectData
import com.workflow.orchestrator.core.model.bamboo.TestResultsData

/**
 * Bamboo operations used by both UI panels and AI agent.
 * Implementations registered as project-level services by :bamboo module.
 */
interface BambooService {
    /**
     * Get the latest build result for a resolved branch-chain key.
     *
     * [chainKey] must be the resolved branch-chain key (e.g. `PROJ-PLANKEY523`)
     * obtained from [com.workflow.orchestrator.core.model.workflow.BuildRef.chainKey]
     * (populated by `WorkflowContextService.focusPr` cascade) or from
     * [com.workflow.orchestrator.core.workflow.ChainKeyResolver.resolveChainKey].
     * Do NOT pass a parent plan key plus a branch — resolve to a chain key first.
     */
    suspend fun getLatestBuild(chainKey: String): ToolResult<BuildResultData>

    /** Get a specific build result with stages. */
    suspend fun getBuild(buildKey: String): ToolResult<BuildResultData>

    /**
     * Trigger a build with optional variables and optional stage selection.
     *
     * @param chainKey the resolved chain/plan key (e.g. `PROJ-BUILD` or `PROJ-BUILD523`).
     * @param variables map of variable name → value overrides.
     * @param stages set of stage names to run. `null` means run all stages (explicit
     *   "run everything" — not a silent default). Empty set is rejected with an error.
     *   When a set is provided, Bamboo runs from the first stage in the set forward
     *   (REST API limitation: only one `stage` param accepted per call).
     */
    suspend fun triggerBuild(
        chainKey: String,
        variables: Map<String, String> = emptyMap(),
        stages: Set<String>? = null
    ): ToolResult<BuildTriggerData>

    /** Test the Bamboo connection. */
    suspend fun testConnection(): ToolResult<Unit>

    /** Get build log text. */
    suspend fun getBuildLog(resultKey: String): ToolResult<String>

    /** Get test results for a build. */
    suspend fun getTestResults(resultKey: String): ToolResult<TestResultsData>

    /** Rerun failed jobs in a build. */
    suspend fun rerunFailedJobs(planKey: String, buildNumber: Int): ToolResult<Unit>

    /** Enable a (disabled) Bamboo plan branch so its jobs/stages can run. */
    suspend fun enablePlanBranch(branchPlanKey: String): ToolResult<Unit>

    /** Get plan variables. */
    suspend fun getPlanVariables(planKey: String): ToolResult<List<PlanVariableData>>

    /**
     * Trigger a specific stage by name.
     *
     * Prefer `triggerBuild(chainKey, variables, stages = setOf(stageName))` for new callers.
     * This overload is retained for the `ManualStageDialog` STAGE-mode path which triggers
     * a single named manual stage and discards the result key (returns `Unit`).
     */
    suspend fun triggerStage(planKey: String, variables: Map<String, String>, stage: String? = null): ToolResult<Unit>

    /** Stop a running build. */
    suspend fun stopBuild(resultKey: String): ToolResult<Unit>

    /** Cancel a queued build. */
    suspend fun cancelBuild(resultKey: String): ToolResult<Unit>

    /** Get artifacts for a build result. */
    suspend fun getArtifacts(resultKey: String): ToolResult<List<com.workflow.orchestrator.core.model.bamboo.ArtifactData>>

    /** Download an artifact to a local file. Returns true on success. */
    suspend fun downloadArtifact(artifactUrl: String, targetFile: java.io.File): ToolResult<Boolean>

    /**
     * Get recent build results for a resolved branch-chain key.
     *
     * [chainKey] must be the resolved branch-chain key (e.g. `PROJ-PLANKEY523`).
     * See [getLatestBuild] for details on how to obtain the chain key.
     */
    suspend fun getRecentBuilds(chainKey: String, maxResults: Int = 10): ToolResult<List<BuildResultData>>

    /** List all plans visible to the authenticated user. */
    suspend fun getPlans(): ToolResult<List<PlanData>>

    /** List plans belonging to a specific project. */
    suspend fun getProjectPlans(projectKey: String): ToolResult<List<PlanData>>

    /** Search plans by name or key. */
    suspend fun searchPlans(query: String): ToolResult<List<PlanData>>

    /**
     * Returns Bamboo's canonical `shortName` for [planKey] (e.g. `"Auto Tests Smoke"`,
     * not the long `"Project - Auto Tests Smoke"` form). Used by the Automation tab
     * to refresh stored suite display names that still carry a long-form prefix
     * from suites added before v0.85.0.
     *
     * Returns the data wrapped in a `ToolResult<String>`. The string is empty
     * when Bamboo's response omits `shortName` (older Bamboo versions);
     * `isError = true` on network/auth/not-found failures.
     */
    suspend fun getPlanShortName(planKey: String): ToolResult<String>

    /**
     * 5-tier waterfall plan detection (T0 local specs → T1 Bitbucket commit-status walk
     * → T2 Bamboo `byChangeset` → T3 Linked Repositories → T4 deep-scan, gated). After
     * any tier hits, [PlanDetectionService.resolveBranchKey] maps the master plan key
     * to its branch plan via `/plan/{master}/branch`.
     *
     * @param repoRoot  local path to the git repository root; null disables Tier 0 + Tier 1
     * @param remoteUrl the git remote URL to match against for Tier 3/4 (SSH or HTTPS)
     * @param branchName current branch name; resolved to a branch plan via `/plan/{master}/branch`
     * @param preferredMaster when supplied, T1 (Bitbucket commit-status walk) prefers
     *   statuses whose extracted plan key starts with this string. Use to disambiguate
     *   multi-module repos where one git remote feeds several Bamboo plans.
     * @return ToolResult with the detected plan key on success, or isError=true
     *         when no plan is found
     */
    suspend fun autoDetectPlan(
        repoRoot: java.nio.file.Path?,
        remoteUrl: String,
        branchName: String? = null,
        preferredMaster: String? = null
    ): ToolResult<String>

    /**
     * Legacy entry point — auto-detects by fetching all plans and scanning bamboo-specs YAML.
     * Delegates to [autoDetectPlan] with null repoRoot.
     *
     * @param gitRemoteUrl the git remote URL to match against (SSH or HTTPS)
     */
    suspend fun autoDetectPlan(gitRemoteUrl: String): ToolResult<String>

    /** List branches for a plan. */
    suspend fun getPlanBranches(planKey: String, repoName: String? = null): ToolResult<List<PlanBranchData>>

    /** Get running and queued builds for a plan. */
    suspend fun getRunningBuilds(planKey: String, repoName: String? = null): ToolResult<List<BuildResultData>>

    /** Get variables used in a specific build result. */
    suspend fun getBuildVariables(resultKey: String): ToolResult<List<PlanVariableData>>

    /** List all projects visible to the authenticated user. */
    suspend fun getProjects(): ToolResult<List<ProjectData>>

    /**
     * Get commit list for a specific build result (R-ADD-1, §8.8 of 2026-05-07 Bamboo audit).
     * Hits GET /result/{key}?expand=changes.change and returns each commit's author,
     * message, SHA, commit URL, and timestamp. Useful for "what's in this build" display
     * and for enriching the Bamboo→Bitbucket bridge with multi-commit PR lookup.
     *
     * Validated on Bamboo 10.2.14: bundle-repo.unpacked/raw/result_changes.json.
     */
    suspend fun getBuildChanges(resultKey: String): ToolResult<List<BuildChangeData>>
}

package com.workflow.orchestrator.core.services

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
    /** Get latest build result for a plan, optionally for a specific branch. */
    suspend fun getLatestBuild(planKey: String, branch: String? = null, repoName: String? = null): ToolResult<BuildResultData>

    /** Get a specific build result with stages. */
    suspend fun getBuild(buildKey: String): ToolResult<BuildResultData>

    /** Trigger a build with optional variables. */
    suspend fun triggerBuild(planKey: String, variables: Map<String, String> = emptyMap()): ToolResult<BuildTriggerData>

    /** Test the Bamboo connection. */
    suspend fun testConnection(): ToolResult<Unit>

    /** Get build log text. */
    suspend fun getBuildLog(resultKey: String): ToolResult<String>

    /** Get test results for a build. */
    suspend fun getTestResults(resultKey: String): ToolResult<TestResultsData>

    /** Rerun failed jobs in a build. */
    suspend fun rerunFailedJobs(planKey: String, buildNumber: Int): ToolResult<Unit>

    /** Get plan variables. */
    suspend fun getPlanVariables(planKey: String): ToolResult<List<PlanVariableData>>

    /** Trigger a specific stage. */
    suspend fun triggerStage(planKey: String, variables: Map<String, String>, stage: String? = null): ToolResult<Unit>

    /** Stop a running build. */
    suspend fun stopBuild(resultKey: String): ToolResult<Unit>

    /** Cancel a queued build. */
    suspend fun cancelBuild(resultKey: String): ToolResult<Unit>

    /** Get artifacts for a build result. */
    suspend fun getArtifacts(resultKey: String): ToolResult<List<com.workflow.orchestrator.core.model.bamboo.ArtifactData>>

    /** Download an artifact to a local file. Returns true on success. */
    suspend fun downloadArtifact(artifactUrl: String, targetFile: java.io.File): ToolResult<Boolean>

    /** Get recent build results for a plan, optionally filtered by branch. */
    suspend fun getRecentBuilds(planKey: String, maxResults: Int = 10, branch: String? = null, repoName: String? = null): ToolResult<List<BuildResultData>>

    /** List all plans visible to the authenticated user. */
    suspend fun getPlans(): ToolResult<List<PlanData>>

    /** List plans belonging to a specific project. */
    suspend fun getProjectPlans(projectKey: String): ToolResult<List<PlanData>>

    /** Search plans by name or key. */
    suspend fun searchPlans(query: String): ToolResult<List<PlanData>>

    /** List branches for a plan. */
    suspend fun getPlanBranches(planKey: String, repoName: String? = null): ToolResult<List<PlanBranchData>>

    /** Get running and queued builds for a plan. */
    suspend fun getRunningBuilds(planKey: String, repoName: String? = null): ToolResult<List<BuildResultData>>

    /** Get variables used in a specific build result. */
    suspend fun getBuildVariables(resultKey: String): ToolResult<List<PlanVariableData>>

    /** List all projects visible to the authenticated user. */
    suspend fun getProjects(): ToolResult<List<ProjectData>>
}

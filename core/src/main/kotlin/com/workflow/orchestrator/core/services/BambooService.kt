package com.workflow.orchestrator.core.services

import com.workflow.orchestrator.core.model.bamboo.BuildResultData
import com.workflow.orchestrator.core.model.bamboo.BuildTriggerData
import com.workflow.orchestrator.core.model.bamboo.TestResultsData
import com.workflow.orchestrator.core.model.bamboo.PlanVariableData

/**
 * Bamboo operations used by both UI panels and AI agent.
 * Implementations registered as project-level services by :bamboo module.
 */
interface BambooService {
    /** Get latest build result for a plan. */
    suspend fun getLatestBuild(planKey: String): ToolResult<BuildResultData>

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

    /** Get recent build results for a plan. */
    suspend fun getRecentBuilds(planKey: String, maxResults: Int = 10): ToolResult<List<BuildResultData>>
}

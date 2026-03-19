package com.workflow.orchestrator.core.services

import com.workflow.orchestrator.core.model.bamboo.BuildResultData
import com.workflow.orchestrator.core.model.bamboo.BuildTriggerData

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
}

package com.workflow.orchestrator.agent.tools.project

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject

/**
 * Stub for set_module_sdk action.
 * Full implementation is in a subsequent task.
 */
internal suspend fun executeSetModuleSdk(
    params: JsonObject,
    project: Project,
    tool: AgentTool,
): ToolResult = ToolResult(
    content = "Error: set_module_sdk is not yet implemented.",
    summary = "Not implemented",
    tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
    isError = true
)

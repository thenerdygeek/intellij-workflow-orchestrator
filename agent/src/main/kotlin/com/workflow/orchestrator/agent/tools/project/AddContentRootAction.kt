package com.workflow.orchestrator.agent.tools.project

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject

/**
 * Stub for add_content_root action.
 * Full implementation is in a subsequent task.
 */
internal suspend fun executeAddContentRoot(
    params: JsonObject,
    project: Project,
    tool: AgentTool,
): ToolResult = ToolResult(
    content = "Error: add_content_root is not yet implemented.",
    summary = "Not implemented",
    tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
    isError = true
)

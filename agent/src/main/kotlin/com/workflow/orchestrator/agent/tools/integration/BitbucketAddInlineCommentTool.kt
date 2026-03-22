package com.workflow.orchestrator.agent.tools.integration

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class BitbucketAddInlineCommentTool : AgentTool {
    override val name = "bitbucket_add_inline_comment"
    override val description = "Add an inline comment to a specific file and line in a Bitbucket pull request."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "pr_id" to ParameterProperty(type = "string", description = "Pull request ID (numeric)"),
            "file_path" to ParameterProperty(type = "string", description = "File path relative to repository root"),
            "line" to ParameterProperty(type = "string", description = "Line number to comment on"),
            "line_type" to ParameterProperty(type = "string", description = "Line type: 'ADDED', 'REMOVED', or 'CONTEXT'"),
            "text" to ParameterProperty(type = "string", description = "Comment text (supports Markdown)")
        ),
        required = listOf("pr_id", "file_path", "line", "line_type", "text")
    )
    override val allowedWorkers = setOf(WorkerType.TOOLER, WorkerType.REVIEWER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val prId = params["pr_id"]?.jsonPrimitive?.content?.toIntOrNull()
            ?: return ToolResult("Error: 'pr_id' must be a valid integer", "Error: invalid pr_id", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val filePath = params["file_path"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'file_path' parameter required", "Error: missing file_path", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val line = params["line"]?.jsonPrimitive?.content?.toIntOrNull()
            ?: return ToolResult("Error: 'line' must be a valid integer", "Error: invalid line", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val lineType = params["line_type"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'line_type' parameter required", "Error: missing line_type", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val text = params["text"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'text' parameter required", "Error: missing text", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        ToolValidation.validateNotBlank(text, "text")?.let { return it }
        val service = ServiceLookup.bitbucket(project) ?: return ServiceLookup.notConfigured("Bitbucket")

        return service.addInlineComment(prId, filePath, line, lineType, text).toAgentToolResult()
    }
}

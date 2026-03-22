package com.workflow.orchestrator.agent.tools.integration

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

class BitbucketReplyToCommentTool : AgentTool {
    override val name = "bitbucket_reply_to_comment"
    override val description = "Reply to an existing comment on a Bitbucket pull request."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "pr_id" to ParameterProperty(type = "string", description = "Pull request ID (numeric)"),
            "parent_comment_id" to ParameterProperty(type = "string", description = "ID of the comment to reply to (numeric)"),
            "text" to ParameterProperty(type = "string", description = "Reply text (supports Markdown)"),
            "repo_name" to ParameterProperty(type = "string", description = "Repository name (e.g., 'backend', 'frontend'). Required for multi-repo projects to target a specific repo. Omit to use the primary repository. Call bitbucket_list_repos to discover available names.")
        ),
        required = listOf("pr_id", "parent_comment_id", "text")
    )
    override val allowedWorkers = setOf(WorkerType.TOOLER, WorkerType.REVIEWER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val prId = params["pr_id"]?.jsonPrimitive?.content?.toIntOrNull()
            ?: return ToolResult("Error: 'pr_id' must be a valid integer", "Error: invalid pr_id", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val parentCommentId = params["parent_comment_id"]?.jsonPrimitive?.content?.toIntOrNull()
            ?: return ToolResult("Error: 'parent_comment_id' must be a valid integer", "Error: invalid parent_comment_id", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val text = params["text"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'text' parameter required", "Error: missing text", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        ToolValidation.validateNotBlank(text, "text")?.let { return it }
        val service = ServiceLookup.bitbucket(project) ?: return ServiceLookup.notConfigured("Bitbucket")

        val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull

        return service.replyToComment(prId, parentCommentId, text, repoName = repoName).toAgentToolResult()
    }
}

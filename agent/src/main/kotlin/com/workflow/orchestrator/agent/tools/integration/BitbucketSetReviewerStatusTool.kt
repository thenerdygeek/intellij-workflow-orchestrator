package com.workflow.orchestrator.agent.tools.integration

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class BitbucketSetReviewerStatusTool : AgentTool {
    override val name = "bitbucket_set_reviewer_status"
    override val description = "Set a reviewer's status on a Bitbucket pull request (APPROVED, NEEDS_WORK, or UNAPPROVED)."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "pr_id" to ParameterProperty(type = "string", description = "Pull request ID (numeric)"),
            "username" to ParameterProperty(type = "string", description = "Reviewer username"),
            "status" to ParameterProperty(type = "string", description = "Review status: 'APPROVED', 'NEEDS_WORK', or 'UNAPPROVED'")
        ),
        required = listOf("pr_id", "username", "status")
    )
    override val allowedWorkers = setOf(WorkerType.TOOLER, WorkerType.REVIEWER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val prId = params["pr_id"]?.jsonPrimitive?.content?.toIntOrNull()
            ?: return ToolResult("Error: 'pr_id' must be a valid integer", "Error: invalid pr_id", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val username = params["username"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'username' parameter required", "Error: missing username", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val status = params["status"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'status' parameter required", "Error: missing status", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        if (status !in setOf("APPROVED", "NEEDS_WORK", "UNAPPROVED")) {
            return ToolResult("Error: 'status' must be APPROVED, NEEDS_WORK, or UNAPPROVED", "Error: invalid status", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }

        val service = ServiceLookup.bitbucket(project) ?: return ServiceLookup.notConfigured("Bitbucket")

        return service.setReviewerStatus(prId, username, status).toAgentToolResult()
    }
}

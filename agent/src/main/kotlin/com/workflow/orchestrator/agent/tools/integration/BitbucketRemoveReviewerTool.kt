package com.workflow.orchestrator.agent.tools.integration

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class BitbucketRemoveReviewerTool : AgentTool {
    override val name = "bitbucket_remove_reviewer"
    override val description = "Remove a reviewer from a Bitbucket pull request."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "pr_id" to ParameterProperty(type = "string", description = "Pull request ID (numeric)"),
            "username" to ParameterProperty(type = "string", description = "Username of the reviewer to remove")
        ),
        required = listOf("pr_id", "username")
    )
    override val allowedWorkers = setOf(WorkerType.TOOLER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val prId = params["pr_id"]?.jsonPrimitive?.content?.toIntOrNull()
            ?: return ToolResult("Error: 'pr_id' must be a valid integer", "Error: invalid pr_id", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val username = params["username"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'username' parameter required", "Error: missing username", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        ToolValidation.validateNotBlank(username, "username")?.let { return it }
        val service = ServiceLookup.bitbucket(project) ?: return ServiceLookup.notConfigured("Bitbucket")

        return service.removeReviewer(prId, username).toAgentToolResult()
    }
}

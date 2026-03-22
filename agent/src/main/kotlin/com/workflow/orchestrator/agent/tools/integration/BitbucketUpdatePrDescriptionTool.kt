package com.workflow.orchestrator.agent.tools.integration

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class BitbucketUpdatePrDescriptionTool : AgentTool {
    override val name = "bitbucket_update_pr_description"
    override val description = "Update the description of a Bitbucket pull request."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "pr_id" to ParameterProperty(type = "string", description = "Pull request ID (numeric)"),
            "description" to ParameterProperty(type = "string", description = "New description text (supports Markdown)")
        ),
        required = listOf("pr_id", "description")
    )
    override val allowedWorkers = setOf(WorkerType.TOOLER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val prId = params["pr_id"]?.jsonPrimitive?.content?.toIntOrNull()
            ?: return ToolResult("Error: 'pr_id' must be a valid integer", "Error: invalid pr_id", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val description = params["description"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'description' parameter required", "Error: missing description", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        val service = ServiceLookup.bitbucket(project) ?: return ServiceLookup.notConfigured("Bitbucket")

        return service.updatePrDescription(prId, description).toAgentToolResult()
    }
}

package com.workflow.orchestrator.agent.tools.integration

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class BitbucketUpdatePrTitleTool : AgentTool {
    override val name = "bitbucket_update_pr_title"
    override val description = "Update the title of a Bitbucket pull request."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "pr_id" to ParameterProperty(type = "string", description = "Pull request ID (numeric)"),
            "new_title" to ParameterProperty(type = "string", description = "New title for the pull request")
        ),
        required = listOf("pr_id", "new_title")
    )
    override val allowedWorkers = setOf(WorkerType.TOOLER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val prId = params["pr_id"]?.jsonPrimitive?.content?.toIntOrNull()
            ?: return ToolResult("Error: 'pr_id' must be a valid integer", "Error: invalid pr_id", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val newTitle = params["new_title"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'new_title' parameter required", "Error: missing new_title", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        ToolValidation.validateNotBlank(newTitle, "new_title")?.let { return it }
        val service = ServiceLookup.bitbucket(project) ?: return ServiceLookup.notConfigured("Bitbucket")

        return service.updatePrTitle(prId, newTitle).toAgentToolResult()
    }
}

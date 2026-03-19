package com.workflow.orchestrator.agent.tools.integration

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class BitbucketPrTool : AgentTool {
    override val name = "bitbucket_create_pr"
    override val description = "Create a pull request on Bitbucket Server."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "title" to ParameterProperty(type = "string", description = "Pull request title"),
            "description" to ParameterProperty(type = "string", description = "Pull request description"),
            "from_branch" to ParameterProperty(type = "string", description = "Source branch name"),
            "to_branch" to ParameterProperty(type = "string", description = "Target branch name (default: master)")
        ),
        required = listOf("title", "description", "from_branch")
    )
    override val allowedWorkers = setOf(WorkerType.TOOLER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val title = params["title"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'title' parameter required", "Error: missing title", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val description = params["description"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'description' parameter required", "Error: missing description", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val fromBranch = params["from_branch"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'from_branch' parameter required", "Error: missing from_branch", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val toBranch = params["to_branch"]?.jsonPrimitive?.content ?: "master"

        val service = ServiceLookup.bitbucket(project)
            ?: return ServiceLookup.notConfigured("Bitbucket")

        val result = service.createPullRequest(title, description, fromBranch, toBranch)
        return result.toAgentToolResult()
    }
}

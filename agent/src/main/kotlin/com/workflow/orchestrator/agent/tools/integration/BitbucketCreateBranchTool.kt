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

class BitbucketCreateBranchTool : AgentTool {
    override val name = "bitbucket_create_branch"
    override val description = "Create a new branch in the Bitbucket repository from a start point (branch name or commit hash)."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "name" to ParameterProperty(type = "string", description = "New branch name (e.g., feature/PROJ-123-fix-auth)"),
            "start_point" to ParameterProperty(type = "string", description = "Start point: branch name, tag, or commit hash to branch from"),
            "repo_name" to ParameterProperty(type = "string", description = "Repository name for multi-repo projects. Omit for single-repo or to use the primary repository.")
        ),
        required = listOf("name", "start_point")
    )
    override val allowedWorkers = setOf(WorkerType.TOOLER, WorkerType.CODER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val name = params["name"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'name' parameter required", "Error: missing name", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val startPoint = params["start_point"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'start_point' parameter required", "Error: missing start_point", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        ToolValidation.validateNotBlank(name, "name")?.let { return it }
        ToolValidation.validateNotBlank(startPoint, "start_point")?.let { return it }

        val service = ServiceLookup.bitbucket(project) ?: return ServiceLookup.notConfigured("Bitbucket")

        val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull

        return service.createBranch(name, startPoint, repoName = repoName).toAgentToolResult()
    }
}

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

class BitbucketPrTool : AgentTool {
    override val name = "bitbucket_create_pr"
    override val description = "Create a pull request on Bitbucket Server. Validates all required fields before creating."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "title" to ParameterProperty(type = "string", description = "Pull request title (clear, concise summary of changes)"),
            "description" to ParameterProperty(type = "string", description = "Pull request description (details of what changed and why)"),
            "from_branch" to ParameterProperty(type = "string", description = "Source branch name (e.g., 'feature/PROJ-123-fix-auth')"),
            "to_branch" to ParameterProperty(type = "string", description = "Target branch name. Optional, defaults to 'master'"),
            "repo_name" to ParameterProperty(type = "string", description = "Repository name (e.g., 'backend', 'frontend'). Required for multi-repo projects to target a specific repo. Omit to use the primary repository. Call bitbucket_list_repos to discover available names.")
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

        // Validate inputs
        ToolValidation.validateNotBlank(title, "title")?.let { return it }
        ToolValidation.validateNotBlank(fromBranch, "from_branch")?.let { return it }

        if (fromBranch == toBranch) {
            return ToolResult(
                content = "Cannot create PR: source branch '$fromBranch' is the same as target branch '$toBranch'.",
                summary = "Same source and target branch", tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
            )
        }

        val service = ServiceLookup.bitbucket(project) ?: return ServiceLookup.notConfigured("Bitbucket")

        val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull

        return service.createPullRequest(title, description, fromBranch, toBranch, repoName = repoName).toAgentToolResult()
    }
}

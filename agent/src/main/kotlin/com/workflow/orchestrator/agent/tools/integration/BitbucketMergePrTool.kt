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

class BitbucketMergePrTool : AgentTool {
    override val name = "bitbucket_merge_pr"
    override val description = "Merge a Bitbucket pull request. Optionally specify merge strategy, whether to delete the source branch, and a custom commit message."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "pr_id" to ParameterProperty(type = "string", description = "Pull request ID (numeric)"),
            "strategy" to ParameterProperty(type = "string", description = "Merge strategy: 'merge-commit', 'squash', or 'ff-only' (optional)"),
            "delete_source_branch" to ParameterProperty(type = "string", description = "Delete source branch after merge: 'true' or 'false' (default: false)"),
            "commit_message" to ParameterProperty(type = "string", description = "Custom merge commit message (optional)"),
            "repo_name" to ParameterProperty(type = "string", description = "Repository name (e.g., 'backend', 'frontend'). Required for multi-repo projects to target a specific repo. Omit to use the primary repository. Call bitbucket_list_repos to discover available names.")
        ),
        required = listOf("pr_id")
    )
    override val allowedWorkers = setOf(WorkerType.TOOLER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val prId = params["pr_id"]?.jsonPrimitive?.content?.toIntOrNull()
            ?: return ToolResult("Error: 'pr_id' must be a valid integer", "Error: invalid pr_id", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val strategy = params["strategy"]?.jsonPrimitive?.content
        val deleteSourceBranch = params["delete_source_branch"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
        val commitMessage = params["commit_message"]?.jsonPrimitive?.content

        val service = ServiceLookup.bitbucket(project) ?: return ServiceLookup.notConfigured("Bitbucket")

        val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull

        return service.mergePullRequest(prId, strategy, deleteSourceBranch, commitMessage, repoName = repoName).toAgentToolResult()
    }
}

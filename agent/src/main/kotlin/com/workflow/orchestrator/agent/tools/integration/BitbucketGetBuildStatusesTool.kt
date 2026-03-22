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

class BitbucketGetBuildStatusesTool : AgentTool {
    override val name = "bitbucket_get_build_statuses"
    override val description = "Get build statuses for a specific commit in Bitbucket. Shows CI/CD pipeline results."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "commit_id" to ParameterProperty(type = "string", description = "Full or abbreviated commit hash"),
            "repo_name" to ParameterProperty(type = "string", description = "Repository name (e.g., 'backend', 'frontend'). Required for multi-repo projects to target a specific repo. Omit to use the primary repository. Call bitbucket_list_repos to discover available names.")
        ),
        required = listOf("commit_id")
    )
    override val allowedWorkers = setOf(WorkerType.TOOLER, WorkerType.ORCHESTRATOR)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val commitId = params["commit_id"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'commit_id' parameter required", "Error: missing commit_id", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        ToolValidation.validateNotBlank(commitId, "commit_id")?.let { return it }
        val service = ServiceLookup.bitbucket(project) ?: return ServiceLookup.notConfigured("Bitbucket")

        val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull

        return service.getBuildStatuses(commitId, repoName = repoName).toAgentToolResult()
    }
}

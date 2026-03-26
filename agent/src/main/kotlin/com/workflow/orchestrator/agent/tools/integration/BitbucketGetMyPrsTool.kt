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

class BitbucketGetMyPrsTool : AgentTool {
    override val name = "bitbucket_get_my_prs"
    override val description = "Retrieve pull requests authored by the current authenticated user from Bitbucket, with optional filtering by state (OPEN, MERGED, or DECLINED). Use this to check the status of your open PRs, find a specific PR you created, or review your recently merged work. Returns OPEN PRs by default if no state filter is specified. This only returns PRs where you are the author — do NOT use this for PRs where you are a reviewer (use bitbucket_get_reviewing_prs instead) or for detailed information about a specific PR (use bitbucket_get_pr_detail instead)."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "state" to ParameterProperty(type = "string", description = "PR state filter: 'OPEN', 'MERGED', or 'DECLINED' (default: OPEN)"),
            "repo_name" to ParameterProperty(type = "string", description = "Repository name (e.g., 'backend', 'frontend'). Required for multi-repo projects to target a specific repo. Omit to use the primary repository. Call bitbucket_list_repos to discover available names.")
        ),
        required = emptyList()
    )
    override val allowedWorkers = setOf(WorkerType.TOOLER, WorkerType.ORCHESTRATOR)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val state = params["state"]?.jsonPrimitive?.content ?: "OPEN"

        val service = ServiceLookup.bitbucket(project) ?: return ServiceLookup.notConfigured("Bitbucket")

        val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull

        return service.getMyPullRequests(state, repoName = repoName).toAgentToolResult()
    }
}

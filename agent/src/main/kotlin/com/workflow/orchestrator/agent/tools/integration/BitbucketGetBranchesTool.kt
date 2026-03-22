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

class BitbucketGetBranchesTool : AgentTool {
    override val name = "bitbucket_get_branches"
    override val description = "List branches in the Bitbucket repository, optionally filtered by name."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "filter" to ParameterProperty(type = "string", description = "Optional name filter to match branches (e.g., 'feature/')"),
            "repo_name" to ParameterProperty(type = "string", description = "Repository name for multi-repo projects. Omit for single-repo or to use the primary repository.")
        ),
        required = emptyList()
    )
    override val allowedWorkers = setOf(WorkerType.TOOLER, WorkerType.ORCHESTRATOR, WorkerType.CODER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val filter = params["filter"]?.jsonPrimitive?.content

        val service = ServiceLookup.bitbucket(project) ?: return ServiceLookup.notConfigured("Bitbucket")

        val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull

        return service.getBranches(filter, repoName = repoName).toAgentToolResult()
    }
}

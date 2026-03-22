package com.workflow.orchestrator.agent.tools.integration

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class BitbucketGetPrChangesTool : AgentTool {
    override val name = "bitbucket_get_pr_changes"
    override val description = "Get the list of changed files in a Bitbucket pull request. Shows file paths, change types (ADD, MODIFY, DELETE), and line counts."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "pr_id" to ParameterProperty(type = "string", description = "Pull request ID (numeric)")
        ),
        required = listOf("pr_id")
    )
    override val allowedWorkers = setOf(WorkerType.TOOLER, WorkerType.ORCHESTRATOR, WorkerType.REVIEWER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val prId = params["pr_id"]?.jsonPrimitive?.content?.toIntOrNull()
            ?: return ToolResult("Error: 'pr_id' must be a valid integer", "Error: invalid pr_id", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        val service = ServiceLookup.bitbucket(project) ?: return ServiceLookup.notConfigured("Bitbucket")

        return service.getPullRequestChanges(prId).toAgentToolResult()
    }
}

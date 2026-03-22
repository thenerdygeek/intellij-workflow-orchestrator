package com.workflow.orchestrator.agent.tools.integration

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class BitbucketGetReviewingPrsTool : AgentTool {
    override val name = "bitbucket_get_reviewing_prs"
    override val description = "Get pull requests where the current user is a reviewer. Optionally filter by state."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "state" to ParameterProperty(type = "string", description = "PR state filter: 'OPEN', 'MERGED', or 'DECLINED' (default: OPEN)")
        ),
        required = emptyList()
    )
    override val allowedWorkers = setOf(WorkerType.TOOLER, WorkerType.ORCHESTRATOR, WorkerType.REVIEWER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val state = params["state"]?.jsonPrimitive?.content ?: "OPEN"

        val service = ServiceLookup.bitbucket(project) ?: return ServiceLookup.notConfigured("Bitbucket")

        return service.getReviewingPullRequests(state).toAgentToolResult()
    }
}

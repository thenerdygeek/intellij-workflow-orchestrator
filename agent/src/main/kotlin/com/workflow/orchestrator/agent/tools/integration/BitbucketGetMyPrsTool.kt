package com.workflow.orchestrator.agent.tools.integration

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class BitbucketGetMyPrsTool : AgentTool {
    override val name = "bitbucket_get_my_prs"
    override val description = "Get pull requests authored by the current user. Optionally filter by state (OPEN, MERGED, DECLINED)."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "state" to ParameterProperty(type = "string", description = "PR state filter: 'OPEN', 'MERGED', or 'DECLINED' (default: OPEN)")
        ),
        required = emptyList()
    )
    override val allowedWorkers = setOf(WorkerType.TOOLER, WorkerType.ORCHESTRATOR)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val state = params["state"]?.jsonPrimitive?.content ?: "OPEN"

        val service = ServiceLookup.bitbucket(project) ?: return ServiceLookup.notConfigured("Bitbucket")

        return service.getMyPullRequests(state).toAgentToolResult()
    }
}

package com.workflow.orchestrator.agent.tools.integration

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class BambooGetRunningBuildsTool : AgentTool {
    override val name = "bamboo_get_running_builds"
    override val description = "Get currently running and queued builds for a Bamboo plan."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "plan_key" to ParameterProperty(type = "string", description = "Bamboo plan key (e.g., PROJ-PLAN)")
        ),
        required = listOf("plan_key")
    )
    override val allowedWorkers = setOf(WorkerType.TOOLER, WorkerType.ORCHESTRATOR)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val planKey = params["plan_key"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'plan_key' parameter required", "Error: missing plan_key", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        ToolValidation.validateBambooPlanKey(planKey)?.let { return it }
        val service = ServiceLookup.bamboo(project) ?: return ServiceLookup.notConfigured("Bamboo")

        return service.getRunningBuilds(planKey).toAgentToolResult()
    }
}

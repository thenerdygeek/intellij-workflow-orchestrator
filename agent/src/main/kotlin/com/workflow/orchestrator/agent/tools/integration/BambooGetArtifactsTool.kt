package com.workflow.orchestrator.agent.tools.integration

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class BambooGetArtifactsTool : AgentTool {
    override val name = "bamboo_get_artifacts"
    override val description = "Get artifacts produced by a Bamboo build result. Lists artifact names, sizes, and download URLs."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "result_key" to ParameterProperty(type = "string", description = "Bamboo build result key (e.g., PROJ-PLAN-123)")
        ),
        required = listOf("result_key")
    )
    override val allowedWorkers = setOf(WorkerType.TOOLER, WorkerType.ORCHESTRATOR)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val resultKey = params["result_key"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'result_key' parameter required", "Error: missing result_key", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        ToolValidation.validateBambooBuildKey(resultKey)?.let { return it }
        val service = ServiceLookup.bamboo(project) ?: return ServiceLookup.notConfigured("Bamboo")

        return service.getArtifacts(resultKey).toAgentToolResult()
    }
}

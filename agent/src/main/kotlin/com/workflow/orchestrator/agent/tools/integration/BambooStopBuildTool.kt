package com.workflow.orchestrator.agent.tools.integration

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class BambooStopBuildTool : AgentTool {
    override val name = "bamboo_stop_build"
    override val description = "Stop a currently running Bamboo build. Use this to abort a build that is in progress."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "result_key" to ParameterProperty(type = "string", description = "Bamboo build result key (e.g., PROJ-PLAN-123)")
        ),
        required = listOf("result_key")
    )
    override val allowedWorkers = setOf(WorkerType.TOOLER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val resultKey = params["result_key"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'result_key' parameter required", "Error: missing result_key", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        ToolValidation.validateBambooBuildKey(resultKey)?.let { return it }
        val service = ServiceLookup.bamboo(project) ?: return ServiceLookup.notConfigured("Bamboo")

        return service.stopBuild(resultKey).toAgentToolResult()
    }
}

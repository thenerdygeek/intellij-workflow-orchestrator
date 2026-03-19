package com.workflow.orchestrator.agent.tools.integration

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class BambooGetTestResultsTool : AgentTool {
    override val name = "bamboo_get_test_results"
    override val description = "Get test results for a Bamboo build: passed, failed, and skipped counts with failed test details."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "build_key" to ParameterProperty(type = "string", description = "Bamboo build result key (e.g., PROJ-PLAN-123)")
        ),
        required = listOf("build_key")
    )
    override val allowedWorkers = setOf(WorkerType.TOOLER, WorkerType.ORCHESTRATOR)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val buildKey = params["build_key"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'build_key' parameter required", "Error: missing build_key", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        ToolValidation.validateBambooBuildKey(buildKey)?.let { return it }
        val service = ServiceLookup.bamboo(project) ?: return ServiceLookup.notConfigured("Bamboo")

        return service.getTestResults(buildKey).toAgentToolResult()
    }
}

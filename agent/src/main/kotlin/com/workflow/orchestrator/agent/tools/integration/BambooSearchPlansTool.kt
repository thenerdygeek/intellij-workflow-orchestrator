package com.workflow.orchestrator.agent.tools.integration

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class BambooSearchPlansTool : AgentTool {
    override val name = "bamboo_search_plans"
    override val description = "Search Bamboo plans by name or key. Useful for finding a plan when the exact key is unknown."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "query" to ParameterProperty(type = "string", description = "Search query (matches plan name or key)")
        ),
        required = listOf("query")
    )
    override val allowedWorkers = setOf(WorkerType.TOOLER, WorkerType.ORCHESTRATOR)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val query = params["query"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'query' parameter required", "Error: missing query", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        ToolValidation.validateNotBlank(query, "query")?.let { return it }
        val service = ServiceLookup.bamboo(project) ?: return ServiceLookup.notConfigured("Bamboo")

        return service.searchPlans(query).toAgentToolResult()
    }
}

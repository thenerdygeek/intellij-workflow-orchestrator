package com.workflow.orchestrator.agent.tools.integration

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class SonarSearchProjectsTool : AgentTool {
    override val name = "sonar_search_projects"
    override val description = "Search for SonarQube projects by name or key. Use this to find the correct project_key for other Sonar tools."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "query" to ParameterProperty(type = "string", description = "Search query — matches project name or key (e.g., 'my-service' or 'com.example')")
        ),
        required = listOf("query")
    )
    override val allowedWorkers = setOf(WorkerType.TOOLER, WorkerType.ANALYZER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val query = params["query"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'query' parameter required", "Error: missing query", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        ToolValidation.validateNotBlank(query, "query")?.let { return it }
        val service = ServiceLookup.sonar(project) ?: return ServiceLookup.notConfigured("SonarQube")

        return service.searchProjects(query).toAgentToolResult()
    }
}

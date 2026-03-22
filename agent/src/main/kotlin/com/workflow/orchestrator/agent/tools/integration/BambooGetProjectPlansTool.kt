package com.workflow.orchestrator.agent.tools.integration

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class BambooGetProjectPlansTool : AgentTool {
    override val name = "bamboo_get_project_plans"
    override val description = "List all Bamboo plans belonging to a specific project."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "project_key" to ParameterProperty(type = "string", description = "Bamboo project key (e.g., PROJ)")
        ),
        required = listOf("project_key")
    )
    override val allowedWorkers = setOf(WorkerType.TOOLER, WorkerType.ORCHESTRATOR)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val projectKey = params["project_key"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'project_key' parameter required", "Error: missing project_key", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        ToolValidation.validateNotBlank(projectKey, "project_key")?.let { return it }
        val service = ServiceLookup.bamboo(project) ?: return ServiceLookup.notConfigured("Bamboo")

        return service.getProjectPlans(projectKey).toAgentToolResult()
    }
}

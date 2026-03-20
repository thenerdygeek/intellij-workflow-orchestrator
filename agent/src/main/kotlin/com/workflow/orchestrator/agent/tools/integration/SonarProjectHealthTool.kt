package com.workflow.orchestrator.agent.tools.integration

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class SonarProjectHealthTool : AgentTool {
    override val name = "sonar_project_health"
    override val description = "Get project-level health metrics from SonarQube: technical debt, maintainability/reliability/security ratings (A-E), duplication percentage, cognitive complexity, and coverage. Provides a bird's eye view before diving into individual issues."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "project_key" to ParameterProperty(type = "string", description = "SonarQube project key")
        ),
        required = listOf("project_key")
    )
    override val allowedWorkers = setOf(WorkerType.TOOLER, WorkerType.ANALYZER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val projectKey = params["project_key"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'project_key' parameter required", "Error: missing project_key", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        ToolValidation.validateNotBlank(projectKey, "project_key")?.let { return it }
        val service = ServiceLookup.sonar(project) ?: return ServiceLookup.notConfigured("SonarQube")

        return service.getProjectHealth(projectKey).toAgentToolResult()
    }
}

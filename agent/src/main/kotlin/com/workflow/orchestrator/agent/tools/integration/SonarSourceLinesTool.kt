package com.workflow.orchestrator.agent.tools.integration

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class SonarSourceLinesTool : AgentTool {
    override val name = "sonar_source_lines"
    override val description = "Get source lines from SonarQube with per-line coverage status. Shows which lines are covered, uncovered, or partially covered."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "component_key" to ParameterProperty(type = "string", description = "SonarQube component key (e.g., 'com.example:my-service:src/main/java/MyClass.java')"),
            "from" to ParameterProperty(type = "string", description = "Start line number (optional)"),
            "to" to ParameterProperty(type = "string", description = "End line number (optional)")
        ),
        required = listOf("component_key")
    )
    override val allowedWorkers = setOf(WorkerType.TOOLER, WorkerType.ANALYZER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val componentKey = params["component_key"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'component_key' parameter required", "Error: missing component_key", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val from = params["from"]?.jsonPrimitive?.content?.toIntOrNull()
        val to = params["to"]?.jsonPrimitive?.content?.toIntOrNull()

        val service = ServiceLookup.sonar(project) ?: return ServiceLookup.notConfigured("SonarQube")

        return service.getSourceLines(componentKey, from, to).toAgentToolResult()
    }
}

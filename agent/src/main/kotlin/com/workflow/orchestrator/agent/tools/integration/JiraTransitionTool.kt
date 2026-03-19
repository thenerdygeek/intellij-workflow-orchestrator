package com.workflow.orchestrator.agent.tools.integration

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class JiraTransitionTool : AgentTool {
    override val name = "jira_transition"
    override val description = "Transition a Jira ticket to a new status. Use jira_get_ticket first to see available transitions and their IDs."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "key" to ParameterProperty(type = "string", description = "Jira ticket key (e.g., PROJ-123)"),
            "transition_id" to ParameterProperty(type = "string", description = "Numeric transition ID. Get available IDs by reading the ticket first (e.g., '21' for 'In Progress', '31' for 'Done')")
        ),
        required = listOf("key", "transition_id")
    )
    override val allowedWorkers = setOf(WorkerType.TOOLER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val key = params["key"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'key' parameter required", "Error: missing key", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val transitionId = params["transition_id"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'transition_id' parameter required", "Error: missing transition_id", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        val service = ServiceLookup.jira(project)
            ?: return ServiceLookup.notConfigured("Jira")

        val result = service.transition(key, transitionId)
        return result.toAgentToolResult()
    }
}

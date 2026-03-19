package com.workflow.orchestrator.agent.tools.integration

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.context.TokenEstimator
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.core.services.JiraService
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class JiraGetTicketTool : AgentTool {
    override val name = "jira_get_ticket"
    override val description = "Get Jira ticket details: summary, status, assignee, type, priority, description, and available transitions."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "key" to ParameterProperty(type = "string", description = "Jira ticket key (e.g., PROJ-123)")
        ),
        required = listOf("key")
    )
    override val allowedWorkers = setOf(WorkerType.TOOLER, WorkerType.ORCHESTRATOR)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val key = params["key"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'key' parameter required", "Error: missing key", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        val service = ServiceLookup.jira(project)
            ?: return ServiceLookup.notConfigured("Jira")

        val result = service.getTicket(key)
        return result.toAgentToolResult()
    }
}

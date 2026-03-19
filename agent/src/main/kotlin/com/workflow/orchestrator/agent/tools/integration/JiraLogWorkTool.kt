package com.workflow.orchestrator.agent.tools.integration

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class JiraLogWorkTool : AgentTool {
    override val name = "jira_log_work"
    override val description = "Log work/time on a Jira ticket. Validates the ticket exists and the time format is correct before logging."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "key" to ParameterProperty(type = "string", description = "Jira ticket key (e.g., PROJ-123)"),
            "time_spent" to ParameterProperty(type = "string", description = "Time in Jira format: '2h', '30m', '1d', '1h 30m', '1w 2d'"),
            "comment" to ParameterProperty(type = "string", description = "Optional worklog comment describing what was done")
        ),
        required = listOf("key", "time_spent")
    )
    override val allowedWorkers = setOf(WorkerType.TOOLER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val key = params["key"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'key' parameter required", "Error: missing key", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val timeSpent = params["time_spent"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'time_spent' parameter required", "Error: missing time_spent", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val comment = params["comment"]?.jsonPrimitive?.content

        ToolValidation.validateJiraKey(key)?.let { return it }
        ToolValidation.validateTimeSpent(timeSpent)?.let { return it }
        val service = ServiceLookup.jira(project) ?: return ServiceLookup.notConfigured("Jira")

        // Pre-flight: verify ticket exists
        val ticketResult = service.getTicket(key)
        if (ticketResult.isError) {
            return ToolResult(
                content = "Cannot log work: ticket $key not found.\n${ticketResult.summary}",
                summary = "Ticket $key not found", tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
            )
        }

        return service.logWork(key, timeSpent, comment).toAgentToolResult()
    }
}

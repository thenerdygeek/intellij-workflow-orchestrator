package com.workflow.orchestrator.agent.tools.integration

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class JiraCommentTool : AgentTool {
    override val name = "jira_comment"
    override val description = "Add a comment to a Jira ticket. Validates the ticket exists before posting."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "key" to ParameterProperty(type = "string", description = "Jira ticket key (e.g., PROJ-123)"),
            "body" to ParameterProperty(type = "string", description = "Comment body text (Jira wiki markup supported)"),
            "description" to ParameterProperty(type = "string", description = "Brief description of what this action does and why (shown to user in approval dialog)")
        ),
        required = listOf("key", "body", "description")
    )
    override val allowedWorkers = setOf(WorkerType.TOOLER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val key = params["key"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'key' parameter required", "Error: missing key", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val body = params["body"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'body' parameter required", "Error: missing body", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        ToolValidation.validateJiraKey(key)?.let { return it }
        ToolValidation.validateNotBlank(body, "body")?.let { return it }

        val service = ServiceLookup.jira(project) ?: return ServiceLookup.notConfigured("Jira")

        // Pre-flight: verify ticket exists
        val ticketResult = service.getTicket(key)
        if (ticketResult.isError) {
            return ToolResult(
                content = "Cannot add comment: ticket $key not found.\n${ticketResult.summary}",
                summary = "Ticket $key not found", tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
            )
        }

        return service.addComment(key, body).toAgentToolResult()
    }
}

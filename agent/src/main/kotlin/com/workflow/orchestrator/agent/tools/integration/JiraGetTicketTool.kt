package com.workflow.orchestrator.agent.tools.integration

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class JiraGetTicketTool : AgentTool {
    override val name = "jira_get_ticket"
    override val description = "Retrieve full details for a Jira ticket by its issue key, including summary, status, assignee, type, priority, description, available workflow transitions, and linked pull requests. Use this before making code changes related to a ticket to understand requirements, before transitioning a ticket to check available transitions, or to verify ticket status and assignment. Do NOT use this for searching tickets by keyword (use jira_search_issues instead) or for viewing sprint-level overviews (use jira_get_sprint_issues instead)."
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

        ToolValidation.validateJiraKey(key)?.let { return it }

        val service = ServiceLookup.jira(project)
            ?: return ServiceLookup.notConfigured("Jira")

        return service.getTicket(key).toAgentToolResult()
    }
}

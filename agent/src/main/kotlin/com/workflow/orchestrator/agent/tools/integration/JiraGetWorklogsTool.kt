package com.workflow.orchestrator.agent.tools.integration

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class JiraGetWorklogsTool : AgentTool {
    override val name = "jira_get_worklogs"
    override val description = "Get worklogs (time tracking entries) for a Jira ticket. Shows who logged time, duration, and comments."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "issue_key" to ParameterProperty(type = "string", description = "Jira issue key (e.g., PROJ-123)")
        ),
        required = listOf("issue_key")
    )
    override val allowedWorkers = setOf(WorkerType.TOOLER, WorkerType.ORCHESTRATOR)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val issueKey = params["issue_key"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'issue_key' parameter required", "Error: missing issue_key", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        ToolValidation.validateJiraKey(issueKey)?.let { return it }
        val service = ServiceLookup.jira(project) ?: return ServiceLookup.notConfigured("Jira")

        return service.getWorklogs(issueKey).toAgentToolResult()
    }
}

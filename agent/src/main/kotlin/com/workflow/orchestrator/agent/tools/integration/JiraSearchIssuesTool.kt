package com.workflow.orchestrator.agent.tools.integration

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class JiraSearchIssuesTool : AgentTool {
    override val name = "jira_search_issues"
    override val description = "Full-text search for Jira issues assigned to the current user. Returns matching tickets with summary, status, and priority."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "text" to ParameterProperty(type = "string", description = "Search text to match against issue summary and description"),
            "max_results" to ParameterProperty(type = "string", description = "Maximum number of results to return (default: 20)")
        ),
        required = listOf("text")
    )
    override val allowedWorkers = setOf(WorkerType.TOOLER, WorkerType.ORCHESTRATOR)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val text = params["text"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'text' parameter required", "Error: missing text", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val maxResults = params["max_results"]?.jsonPrimitive?.content?.toIntOrNull() ?: 20

        ToolValidation.validateNotBlank(text, "text")?.let { return it }
        val service = ServiceLookup.jira(project) ?: return ServiceLookup.notConfigured("Jira")

        return service.searchIssues(text, maxResults).toAgentToolResult()
    }
}

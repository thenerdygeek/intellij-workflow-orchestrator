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
    override val description = "Full-text search across Jira issue summaries and descriptions to find tickets matching a keyword or phrase, returning matching issues with their key, summary, status, and priority. Use this to find tickets by keyword when you do not know the exact issue key, or to locate related work items for a feature or bug. This only searches issues assigned to the current authenticated user, so it will not find tickets assigned to others. Do NOT use this if you already know the issue key (use jira_get_ticket for direct lookup) or if you need all issues in a sprint (use jira_get_sprint_issues for sprint-scoped queries)."
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

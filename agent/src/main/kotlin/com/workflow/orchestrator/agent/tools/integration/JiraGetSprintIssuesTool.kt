package com.workflow.orchestrator.agent.tools.integration

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class JiraGetSprintIssuesTool : AgentTool {
    override val name = "jira_get_sprint_issues"
    override val description = "Get all issues in a Jira sprint. Shows ticket key, summary, status, assignee, and priority."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "sprint_id" to ParameterProperty(type = "string", description = "Jira sprint ID (numeric)")
        ),
        required = listOf("sprint_id")
    )
    override val allowedWorkers = setOf(WorkerType.TOOLER, WorkerType.ORCHESTRATOR)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val sprintId = params["sprint_id"]?.jsonPrimitive?.content?.toIntOrNull()
            ?: return ToolResult("Error: 'sprint_id' must be a valid integer", "Error: invalid sprint_id", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        val service = ServiceLookup.jira(project) ?: return ServiceLookup.notConfigured("Jira")

        return service.getSprintIssues(sprintId).toAgentToolResult()
    }
}

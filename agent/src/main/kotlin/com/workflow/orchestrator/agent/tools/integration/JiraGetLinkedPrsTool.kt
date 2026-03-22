package com.workflow.orchestrator.agent.tools.integration

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class JiraGetLinkedPrsTool : AgentTool {
    override val name = "jira_get_linked_prs"
    override val description = "Get pull requests linked to a Jira ticket via dev-status. Shows PR title, status, source/target branches, and repository."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "issue_id" to ParameterProperty(type = "string", description = "Jira issue ID (numeric) or issue key")
        ),
        required = listOf("issue_id")
    )
    override val allowedWorkers = setOf(WorkerType.TOOLER, WorkerType.ORCHESTRATOR)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val issueId = params["issue_id"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'issue_id' parameter required", "Error: missing issue_id", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        ToolValidation.validateNotBlank(issueId, "issue_id")?.let { return it }
        val service = ServiceLookup.jira(project) ?: return ServiceLookup.notConfigured("Jira")

        return service.getLinkedPullRequests(issueId).toAgentToolResult()
    }
}

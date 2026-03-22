package com.workflow.orchestrator.agent.tools.integration

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class JiraStartWorkTool : AgentTool {
    override val name = "jira_start_work"
    override val description = "Start work on a Jira ticket: transitions to In Progress and returns a branch name for development."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "issue_key" to ParameterProperty(type = "string", description = "Jira issue key (e.g., PROJ-123)"),
            "branch_name" to ParameterProperty(type = "string", description = "Branch name to create (e.g., feature/PROJ-123-fix-auth)"),
            "source_branch" to ParameterProperty(type = "string", description = "Source branch to create from (e.g., master, develop)")
        ),
        required = listOf("issue_key", "branch_name", "source_branch")
    )
    override val allowedWorkers = setOf(WorkerType.TOOLER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val issueKey = params["issue_key"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'issue_key' parameter required", "Error: missing issue_key", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val branchName = params["branch_name"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'branch_name' parameter required", "Error: missing branch_name", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val sourceBranch = params["source_branch"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'source_branch' parameter required", "Error: missing source_branch", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        ToolValidation.validateJiraKey(issueKey)?.let { return it }
        ToolValidation.validateNotBlank(branchName, "branch_name")?.let { return it }
        ToolValidation.validateNotBlank(sourceBranch, "source_branch")?.let { return it }

        val service = ServiceLookup.jira(project) ?: return ServiceLookup.notConfigured("Jira")

        return service.startWork(issueKey, branchName, sourceBranch).toAgentToolResult()
    }
}

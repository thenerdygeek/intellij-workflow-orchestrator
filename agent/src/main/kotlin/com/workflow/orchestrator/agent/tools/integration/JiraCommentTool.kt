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
    override val description = "Add a comment to a Jira ticket."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "key" to ParameterProperty(type = "string", description = "Jira ticket key (e.g., PROJ-123)"),
            "body" to ParameterProperty(type = "string", description = "Comment body text (Jira wiki markup supported)")
        ),
        required = listOf("key", "body")
    )
    override val allowedWorkers = setOf(WorkerType.TOOLER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val key = params["key"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'key' parameter required", "Error: missing key", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val body = params["body"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'body' parameter required", "Error: missing body", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        val service = ServiceLookup.jira(project)
            ?: return ServiceLookup.notConfigured("Jira")

        val result = service.addComment(key, body)
        return result.toAgentToolResult()
    }
}

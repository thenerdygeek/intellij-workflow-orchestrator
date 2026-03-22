package com.workflow.orchestrator.agent.tools.integration

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class JiraGetSprintsTool : AgentTool {
    override val name = "jira_get_sprints"
    override val description = "Get available sprints (closed and active) for a Jira board. Shows sprint name, state, start and end dates."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "board_id" to ParameterProperty(type = "string", description = "Jira board ID (numeric)")
        ),
        required = listOf("board_id")
    )
    override val allowedWorkers = setOf(WorkerType.TOOLER, WorkerType.ORCHESTRATOR)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val boardId = params["board_id"]?.jsonPrimitive?.content?.toIntOrNull()
            ?: return ToolResult("Error: 'board_id' must be a valid integer", "Error: invalid board_id", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        val service = ServiceLookup.jira(project) ?: return ServiceLookup.notConfigured("Jira")

        return service.getAvailableSprints(boardId).toAgentToolResult()
    }
}

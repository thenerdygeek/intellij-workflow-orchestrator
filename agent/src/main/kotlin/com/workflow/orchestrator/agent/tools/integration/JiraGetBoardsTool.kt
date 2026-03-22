package com.workflow.orchestrator.agent.tools.integration

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class JiraGetBoardsTool : AgentTool {
    override val name = "jira_get_boards"
    override val description = "Get Jira boards, optionally filtered by type (scrum/kanban) and name. Shows board ID, name, and type."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "type" to ParameterProperty(type = "string", description = "Board type filter: 'scrum' or 'kanban' (optional)"),
            "name_filter" to ParameterProperty(type = "string", description = "Filter boards by name (optional)")
        ),
        required = emptyList()
    )
    override val allowedWorkers = setOf(WorkerType.TOOLER, WorkerType.ORCHESTRATOR)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val type = params["type"]?.jsonPrimitive?.content
        val nameFilter = params["name_filter"]?.jsonPrimitive?.content

        val service = ServiceLookup.jira(project) ?: return ServiceLookup.notConfigured("Jira")

        return service.getBoards(type, nameFilter).toAgentToolResult()
    }
}

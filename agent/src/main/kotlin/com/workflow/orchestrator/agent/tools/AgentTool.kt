package com.workflow.orchestrator.agent.tools

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionDefinition
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ToolDefinition
import com.workflow.orchestrator.agent.runtime.WorkerType
import kotlinx.serialization.json.JsonObject

interface AgentTool {
    val name: String
    val description: String
    val parameters: FunctionParameters
    val allowedWorkers: Set<WorkerType>

    suspend fun execute(params: JsonObject, project: Project): ToolResult

    fun toToolDefinition(): ToolDefinition = ToolDefinition(
        function = FunctionDefinition(
            name = name,
            description = description,
            parameters = parameters
        )
    )
}

data class ToolResult(
    val content: String,
    val summary: String,
    val tokenEstimate: Int,
    val artifacts: List<String> = emptyList(),
    val isError: Boolean = false
)

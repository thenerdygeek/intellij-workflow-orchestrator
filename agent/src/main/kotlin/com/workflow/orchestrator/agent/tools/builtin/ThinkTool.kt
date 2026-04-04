package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class ThinkTool : AgentTool {
    override val name = "think"
    override val description = "Use this tool to think about something before acting. It will not obtain new information or change any files, but lets you reason through complex decisions. Use when: analyzing tool output, planning multi-step changes, choosing between approaches, or before making irreversible edits."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "thought" to ParameterProperty(type = "string", description = "Your reasoning or analysis. Think through the problem step by step.")
        ),
        required = listOf("thought")
    )
    override val allowedWorkers = setOf(WorkerType.ORCHESTRATOR, WorkerType.CODER, WorkerType.ANALYZER, WorkerType.REVIEWER, WorkerType.TOOLER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val thought = params["thought"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'thought' parameter required", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        return ToolResult(content = "Thought recorded.", summary = "Thought recorded", tokenEstimate = 2)
    }
}

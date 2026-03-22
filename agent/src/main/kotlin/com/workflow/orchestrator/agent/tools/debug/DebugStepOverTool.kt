package com.workflow.orchestrator.agent.tools.debug

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.context.TokenEstimator
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Steps over the current line in the debug session (execute current line
 * without entering method calls). Returns new position and top-frame variables.
 */
class DebugStepOverTool(
    private val controller: AgentDebugController
) : AgentTool {
    override val name = "debug_step_over"
    override val description = "Step over the current line in a debug session. Returns new position and top-frame variables."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "session_id" to ParameterProperty(
                type = "string",
                description = "Debug session ID (optional — uses active session if omitted)"
            )
        ),
        required = emptyList()
    )
    override val allowedWorkers = setOf(WorkerType.CODER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val sessionId = params["session_id"]?.jsonPrimitive?.content
        return executeStep(controller, sessionId, "step_over") { session ->
            session.stepOver(false)
        }
    }
}

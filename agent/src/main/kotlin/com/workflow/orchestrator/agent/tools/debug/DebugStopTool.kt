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
 * Stops a debug session, terminating the debugged process.
 */
class DebugStopTool(
    private val controller: AgentDebugController
) : AgentTool {
    override val name = "debug_stop"
    override val description = "Stop a debug session and terminate the debugged process."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "session_id" to ParameterProperty(
                type = "string",
                description = "Debug session ID (optional — uses active session if omitted)"
            ),
            "description" to ParameterProperty(
                type = "string",
                description = "Brief description of what this action does and why (shown to user in approval dialog)"
            )
        ),
        required = listOf("description")
    )
    override val allowedWorkers = setOf(WorkerType.CODER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val sessionId = params["session_id"]?.jsonPrimitive?.content

        return try {
            val resolvedId = sessionId ?: controller.getActiveSessionId()
            val session = controller.getSession(sessionId)
                ?: return ToolResult(
                    "No debug session found${sessionId?.let { ": $it" } ?: ""}. Start one with start_debug_session.",
                    "No session",
                    ToolResult.ERROR_TOKEN_ESTIMATE,
                    isError = true
                )

            session.stop()

            val content = "Debug session stopped. Session: ${resolvedId ?: "unknown"}"
            ToolResult(content, "Debug session stopped", TokenEstimator.estimate(content))
        } catch (e: Exception) {
            ToolResult(
                "Error stopping debug session: ${e.message}",
                "Error",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }
    }
}

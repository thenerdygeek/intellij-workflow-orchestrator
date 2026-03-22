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
 * Pauses a running debug session. Waits up to 5 seconds for the session
 * to actually suspend, then returns the pause location if available.
 */
class DebugPauseTool(
    private val controller: AgentDebugController
) : AgentTool {
    override val name = "debug_pause"
    override val description = "Pause a running debug session. Returns pause location if suspension completes within timeout."
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

        return try {
            val resolvedId = sessionId ?: controller.getActiveSessionId()
            val session = controller.getSession(sessionId)
                ?: return ToolResult(
                    "No debug session found${sessionId?.let { ": $it" } ?: ""}. Start one with start_debug_session.",
                    "No session",
                    ToolResult.ERROR_TOKEN_ESTIMATE,
                    isError = true
                )

            session.pause()

            val id = resolvedId ?: "unknown"
            val pauseEvent = controller.waitForPause(id, 5000)

            val content = if (pauseEvent != null) {
                "Session paused at ${pauseEvent.file ?: "unknown"}:${pauseEvent.line ?: "?"}\nSession: $id"
            } else {
                "Pause requested. Session: $id"
            }

            ToolResult(content, "Pause requested", TokenEstimator.estimate(content))
        } catch (e: Exception) {
            ToolResult(
                "Error pausing debug session: ${e.message}",
                "Error",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }
    }
}

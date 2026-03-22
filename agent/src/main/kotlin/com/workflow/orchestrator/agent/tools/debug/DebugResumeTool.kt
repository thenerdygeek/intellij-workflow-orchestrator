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
 * Resumes execution of a paused debug session. The session will run
 * until the next breakpoint or program termination.
 */
class DebugResumeTool(
    private val controller: AgentDebugController
) : AgentTool {
    override val name = "debug_resume"
    override val description = "Resume execution of a paused debug session. Runs until next breakpoint or termination."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "session_id" to ParameterProperty(
                type = "string",
                description = "Debug session ID (optional — uses active session if omitted)"
            )
        ),
        required = emptyList()
    )
    override val allowedWorkers = setOf(WorkerType.CODER, WorkerType.ANALYZER)

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

            session.resume()

            val content = "Session resumed. Session: ${resolvedId ?: "unknown"}"
            ToolResult(content, "Session resumed", TokenEstimator.estimate(content))
        } catch (e: Exception) {
            ToolResult(
                "Error resuming debug session: ${e.message}",
                "Error",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }
    }
}

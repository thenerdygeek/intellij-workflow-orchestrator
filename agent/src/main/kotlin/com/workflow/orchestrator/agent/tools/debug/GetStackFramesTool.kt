package com.workflow.orchestrator.agent.tools.debug

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.context.TokenEstimator
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Gets the full stack trace of the paused thread in a debug session.
 * Read-only inspection tool that delegates to AgentDebugController.
 */
class GetStackFramesTool(
    private val controller: AgentDebugController
) : AgentTool {
    override val name = "get_stack_frames"
    override val description = "Get the full stack trace of the paused thread in a debug session"
    override val parameters = FunctionParameters(
        properties = mapOf(
            "session_id" to ParameterProperty(
                type = "string",
                description = "Debug session ID (optional — uses active session if omitted)"
            ),
            "thread_name" to ParameterProperty(
                type = "string",
                description = "Thread name to get stack from (optional — uses active thread if omitted)"
            ),
            "max_frames" to ParameterProperty(
                type = "integer",
                description = "Maximum number of stack frames to return (default: 20, max: 50)"
            )
        ),
        required = emptyList()
    )
    override val allowedWorkers = setOf(WorkerType.CODER, WorkerType.REVIEWER, WorkerType.ANALYZER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val sessionId = params["session_id"]?.jsonPrimitive?.content
        val maxFrames = (params["max_frames"]?.jsonPrimitive?.intOrNull ?: DEFAULT_MAX_FRAMES)
            .coerceIn(1, MAX_FRAMES_CAP)

        val session = controller.getSession(sessionId)
            ?: return ToolResult(
                "No debug session found${sessionId?.let { ": $it" } ?: ""}. Start one with start_debug_session.",
                "No session",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        if (!session.isSuspended) {
            return ToolResult(
                "Session is not suspended. Cannot get stack frames while running.",
                "Not suspended",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }

        return try {
            val frames = controller.getStackFrames(session, maxFrames)

            if (frames.isEmpty()) {
                return ToolResult(
                    "No stack frames available.",
                    "No frames",
                    ToolResult.ERROR_TOKEN_ESTIMATE
                )
            }

            val threadName = params["thread_name"]?.jsonPrimitive?.content
                ?: session.suspendContext?.activeExecutionStack?.displayName
                ?: "main"

            val sb = StringBuilder()
            sb.append("Stack trace ($threadName thread, ${frames.size} frames):\n")

            for (frame in frames) {
                val location = buildString {
                    append(frame.methodName)
                    if (frame.file != null && frame.line != null) {
                        val fileName = frame.file.substringAfterLast('/')
                        append("($fileName:${frame.line})")
                    }
                }
                sb.append("#${frame.index}  $location\n")
            }

            val content = sb.toString().trimEnd()
            ToolResult(content, "Stack trace: ${frames.size} frames", TokenEstimator.estimate(content))
        } catch (e: Exception) {
            ToolResult(
                "Error getting stack frames: ${e.message}",
                "Error",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }
    }

    companion object {
        const val DEFAULT_MAX_FRAMES = 20
        const val MAX_FRAMES_CAP = 50
    }
}

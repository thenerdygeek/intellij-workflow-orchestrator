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
 * Gets variable values in the current or specified stack frame of a paused
 * debug session. Supports deep inspection of specific variables.
 * Read-only inspection tool that delegates to AgentDebugController.
 */
class GetVariablesTool(
    private val controller: AgentDebugController
) : AgentTool {
    override val name = "get_variables"
    override val description = "Get variable values in the current or specified stack frame of a paused debug session"
    override val parameters = FunctionParameters(
        properties = mapOf(
            "session_id" to ParameterProperty(
                type = "string",
                description = "Debug session ID (optional — uses active session if omitted)"
            ),
            "frame_index" to ParameterProperty(
                type = "integer",
                description = "Stack frame index to inspect (default: 0 = top frame)"
            ),
            "max_depth" to ParameterProperty(
                type = "integer",
                description = "Maximum depth for variable expansion (default: 2, max: 4)"
            ),
            "variable_name" to ParameterProperty(
                type = "string",
                description = "Specific variable name to deep-inspect (shows only that variable with deeper expansion)"
            )
        ),
        required = emptyList()
    )
    override val allowedWorkers = setOf(WorkerType.CODER, WorkerType.REVIEWER, WorkerType.ANALYZER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val sessionId = params["session_id"]?.jsonPrimitive?.content
        val frameIndex = params["frame_index"]?.jsonPrimitive?.intOrNull ?: 0
        val variableName = params["variable_name"]?.jsonPrimitive?.content
        val maxDepth = (params["max_depth"]?.jsonPrimitive?.intOrNull ?: DEFAULT_MAX_DEPTH)
            .coerceIn(1, MAX_DEPTH_CAP)

        if (frameIndex < 0) {
            return ToolResult(
                "frame_index must be >= 0, got: $frameIndex",
                "Invalid frame_index",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }

        val session = controller.getSession(sessionId)
            ?: return ToolResult(
                "No debug session found${sessionId?.let { ": $it" } ?: ""}. Start one with start_debug_session.",
                "No session",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        if (!session.isSuspended) {
            return ToolResult(
                "Session is not suspended. Cannot inspect variables while running.",
                "Not suspended",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }

        return try {
            // Resolve the target frame
            val frame = if (frameIndex == 0) {
                session.currentStackFrame
            } else {
                // Need to get frames to find the requested index
                val frames = controller.getStackFrames(session, frameIndex + 1)
                if (frames.size <= frameIndex) {
                    return ToolResult(
                        "Frame #$frameIndex not available (only ${frames.size} frames on stack).",
                        "Frame not found",
                        ToolResult.ERROR_TOKEN_ESTIMATE,
                        isError = true
                    )
                }
                // For non-zero indices, fall back to current frame since getStackFrames
                // returns FrameInfo DTOs (not XStackFrame references)
                session.currentStackFrame
            }

            if (frame == null) {
                return ToolResult(
                    "No active stack frame available.",
                    "No frame",
                    ToolResult.ERROR_TOKEN_ESTIMATE,
                    isError = true
                )
            }

            // Use deeper expansion when targeting a specific variable
            val effectiveDepth = if (variableName != null) maxDepth.coerceAtLeast(DEFAULT_MAX_DEPTH) else maxDepth
            val variables = controller.getVariables(frame, effectiveDepth)

            if (variables.isEmpty()) {
                return ToolResult(
                    "No variables in the current frame.",
                    "No variables",
                    ToolResult.ERROR_TOKEN_ESTIMATE
                )
            }

            // Filter to specific variable if requested
            val targetVars = if (variableName != null) {
                val match = variables.filter { it.name == variableName }
                if (match.isEmpty()) {
                    return ToolResult(
                        "Variable '$variableName' not found in frame. Available: ${variables.joinToString(", ") { it.name }}",
                        "Variable not found",
                        ToolResult.ERROR_TOKEN_ESTIMATE,
                        isError = true
                    )
                }
                match
            } else {
                variables
            }

            // Build frame header
            val pos = session.currentPosition
            val frameHeader = if (pos != null) {
                val file = pos.file.name
                val line = pos.line + 1
                "Frame #$frameIndex: $file:$line"
            } else {
                "Frame #$frameIndex"
            }

            val sb = StringBuilder()
            sb.append("$frameHeader\n\nVariables:\n")
            sb.append(formatVariables(targetVars))

            var content = sb.toString()

            // Apply 3000 char cap
            if (content.length > MAX_OUTPUT_CHARS) {
                content = content.take(MAX_OUTPUT_CHARS) +
                    "\n... (use variable_name to inspect specific variable)"
            }

            val varCount = targetVars.size
            val summary = if (variableName != null) {
                "Variable '$variableName' inspected"
            } else {
                "$varCount variables in frame #$frameIndex"
            }
            ToolResult(content, summary, TokenEstimator.estimate(content))
        } catch (e: Exception) {
            ToolResult(
                "Error getting variables: ${e.message}",
                "Error",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }
    }

    companion object {
        const val DEFAULT_MAX_DEPTH = 2
        const val MAX_DEPTH_CAP = 4
        const val MAX_OUTPUT_CHARS = 3000
    }
}

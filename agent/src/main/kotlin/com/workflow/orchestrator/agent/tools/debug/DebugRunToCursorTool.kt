package com.workflow.orchestrator.agent.tools.debug

import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.XSourcePosition
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.context.TokenEstimator
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.builtin.PathValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
/**
 * Runs the debug session until execution reaches a specific file and line.
 * Equivalent to "Run to Cursor" in the IDE. Waits up to 30 seconds for
 * the target location to be reached.
 */
class DebugRunToCursorTool(
    private val controller: AgentDebugController
) : AgentTool {
    override val name = "debug_run_to_cursor"
    override val description = "Run to a specific file and line in a debug session. Equivalent to 'Run to Cursor'."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "file" to ParameterProperty(
                type = "string",
                description = "File path (relative to project or absolute)"
            ),
            "line" to ParameterProperty(
                type = "integer",
                description = "1-based line number to run to"
            ),
            "session_id" to ParameterProperty(
                type = "string",
                description = "Debug session ID (optional — uses active session if omitted)"
            )
        ),
        required = listOf("file", "line")
    )
    override val allowedWorkers = setOf(WorkerType.CODER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val filePath = params["file"]?.jsonPrimitive?.content
            ?: return ToolResult(
                "Missing required parameter: file",
                "Missing param",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        val line = params["line"]?.jsonPrimitive?.intOrNull
            ?: return ToolResult(
                "Missing or invalid required parameter: line",
                "Missing param",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        val sessionId = params["session_id"]?.jsonPrimitive?.content

        if (line < 1) {
            return ToolResult(
                "Line number must be >= 1, got: $line",
                "Invalid line",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }

        return try {
            val resolvedId = sessionId ?: controller.getActiveSessionId()
            val session = controller.getSession(sessionId)
                ?: return ToolResult(
                    "No debug session found${sessionId?.let { ": $it" } ?: ""}. Start one with start_debug_session.",
                    "No session",
                    ToolResult.ERROR_TOKEN_ESTIMATE,
                    isError = true
                )

            if (!session.isSuspended) {
                return ToolResult(
                    "Session is not suspended. Pause or wait for a breakpoint first.",
                    "Not suspended",
                    ToolResult.ERROR_TOKEN_ESTIMATE,
                    isError = true
                )
            }

            // Resolve and validate file path (prevents path traversal)
            val (absolutePath, pathError) = PathValidator.resolveAndValidate(filePath, project.basePath)
            if (pathError != null) return pathError

            // Create XSourcePosition on EDT
            val position = withContext(Dispatchers.EDT) {
                val vFile = LocalFileSystem.getInstance().findFileByPath(absolutePath!!)
                    ?: return@withContext null
                XDebuggerUtil.getInstance().createPosition(vFile, line - 1) // 0-based
            } ?: return ToolResult(
                "File not found: $absolutePath",
                "File not found",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

            // Run to the position
            session.runToPosition(position, false)

            val id = resolvedId ?: "unknown"
            val pauseEvent = controller.waitForPause(id, 30000)

            val content = if (pauseEvent != null) {
                "Reached ${pauseEvent.file ?: "unknown"}:${pauseEvent.line ?: "?"}\nSession: $id"
            } else {
                "Run to cursor requested ($filePath:$line). Session did not pause within 30s.\nSession: $id"
            }

            ToolResult(content, "Run to cursor", TokenEstimator.estimate(content))
        } catch (e: Exception) {
            ToolResult(
                "Error running to cursor: ${e.message}",
                "Error",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }
    }
}

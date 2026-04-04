package com.workflow.orchestrator.agent.tools.debug

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.xdebugger.XDebuggerUtil
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.core.ai.TokenEstimator
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.builtin.PathValidator
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import com.intellij.openapi.application.EDT
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * Debug session navigation — stepping, state, and lifecycle control.
 *
 * 8 actions with only 4 parameters (action, session_id, file, line).
 */
class DebugStepTool(private val controller: AgentDebugController) : AgentTool {

    override val name = "debug_step"

    override val description = """
Debug session navigation — stepping, state, and lifecycle control.

Actions and their parameters:
- get_state(session_id?) → Current breakpoint, thread, and line info
- step_over(session_id?) → Step over current line
- step_into(session_id?) → Step into method call
- step_out(session_id?) → Step out of current method
- resume(session_id?) → Resume execution
- pause(session_id?) → Pause execution
- run_to_cursor(file, line, session_id?) → Run to specific line
- stop(session_id?) → Stop debug session

All actions accept optional session_id (defaults to active session).
""".trimIndent()

    override val parameters = FunctionParameters(
        properties = mapOf(
            "action" to ParameterProperty(
                type = "string",
                description = "Operation to perform",
                enumValues = listOf(
                    "get_state", "step_over", "step_into", "step_out",
                    "resume", "pause", "run_to_cursor", "stop"
                )
            ),
            "session_id" to ParameterProperty(
                type = "string",
                description = "Debug session ID (optional — uses active session if omitted)"
            ),
            "file" to ParameterProperty(
                type = "string",
                description = "File path relative to project or absolute — for run_to_cursor"
            ),
            "line" to ParameterProperty(
                type = "integer",
                description = "1-based line number — for run_to_cursor"
            ),
            "description" to ParameterProperty(
                type = "string",
                description = "Brief description of what this action does and why (shown to user in approval dialog)"
            )
        ),
        required = listOf("action")
    )

    override val allowedWorkers = setOf(WorkerType.CODER, WorkerType.REVIEWER, WorkerType.ANALYZER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        coroutineContext.ensureActive()
        val action = params["action"]?.jsonPrimitive?.content
            ?: return ToolResult(
                "Error: 'action' parameter required",
                "Error: missing action",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        return when (action) {
            "get_state" -> executeGetState(params, project)
            "step_over" -> executeStepAction(params, "step_over") { it.stepOver(false) }
            "step_into" -> executeStepAction(params, "step_into") { it.stepInto() }
            "step_out" -> executeStepAction(params, "step_out") { it.stepOut() }
            "resume" -> executeResume(params, project)
            "pause" -> executePause(params, project)
            "run_to_cursor" -> executeRunToCursor(params, project)
            "stop" -> executeStop(params, project)
            else -> ToolResult(
                content = "Unknown action '$action'. Valid actions: get_state, step_over, step_into, step_out, resume, pause, run_to_cursor, stop",
                summary = "Unknown action '$action'",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }
    }

    // ── get_state ───────────────────────────────────────────────────────────

    private suspend fun executeGetState(params: JsonObject, project: Project): ToolResult {
        val sessionId = params["session_id"]?.jsonPrimitive?.content
        val resolvedId = sessionId ?: controller.getActiveSessionId()

        val session = controller.getSession(sessionId)
            ?: return ToolResult(
                "No debug session found${sessionId?.let { ": $it" } ?: ""}. Start one with start_debug_session.",
                "No session",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        return try {
            val sb = StringBuilder()
            sb.append("Session: ${resolvedId ?: "unknown"}\n")

            val isStopped = session.isStopped
            val isSuspended = session.isSuspended
            val pos = session.currentPosition

            val status = when {
                isStopped -> "STOPPED"
                isSuspended && pos != null -> {
                    val file = pos.file.name
                    val line = pos.line + 1
                    "PAUSED at $file:$line"
                }
                isSuspended -> "PAUSED"
                else -> "RUNNING"
            }
            sb.append("Status: $status\n")

            if (isSuspended) {
                sb.append("Reason: breakpoint\n")
            }

            val suspendContext = session.suspendContext
            if (suspendContext != null && isSuspended) {
                val activeStack = suspendContext.activeExecutionStack
                val allStacks = suspendContext.executionStacks

                val totalThreads = allStacks.size
                val suspendedCount = allStacks.size.coerceAtLeast(1)
                sb.append("Suspended threads: $suspendedCount of $totalThreads\n")

                if (activeStack != null) {
                    val threadName = activeStack.displayName
                    val frameDesc = if (pos != null) {
                        val currentFrame = session.currentStackFrame
                        val file = pos.file.name
                        val line = pos.line + 1
                        "$currentFrame".takeIf { it != "null" } ?: "$file:$line"
                    } else {
                        "unknown position"
                    }
                    sb.append("  $threadName (SUSPENDED) at $frameDesc\n")
                }

                allStacks.filter { it != activeStack }.take(5).forEach { stack ->
                    sb.append("  ${stack.displayName}\n")
                }
            }

            val content = sb.toString().trimEnd()
            ToolResult(content, "Debug state: $status", TokenEstimator.estimate(content))
        } catch (e: Exception) {
            ToolResult("Error getting debug state: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

    // ── step_over / step_into / step_out ────────────────────────────────────

    private suspend fun executeStepAction(
        params: JsonObject,
        actionName: String,
        action: (com.intellij.xdebugger.XDebugSession) -> Unit
    ): ToolResult {
        val sessionId = params["session_id"]?.jsonPrimitive?.content
        return executeStep(controller, sessionId, actionName, action)
    }

    // ── resume ──────────────────────────────────────────────────────────────

    private suspend fun executeResume(params: JsonObject, project: Project): ToolResult {
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
            ToolResult("Error resuming debug session: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

    // ── pause ───────────────────────────────────────────────────────────────

    private suspend fun executePause(params: JsonObject, project: Project): ToolResult {
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
            ToolResult("Error pausing debug session: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

    // ── run_to_cursor ───────────────────────────────────────────────────────

    private suspend fun executeRunToCursor(params: JsonObject, project: Project): ToolResult {
        val filePath = params["file"]?.jsonPrimitive?.content ?: return missingParam("file")
        val line = params["line"]?.jsonPrimitive?.intOrNull ?: return missingParam("line")
        val sessionId = params["session_id"]?.jsonPrimitive?.content

        if (line < 1) {
            return ToolResult("Line number must be >= 1, got: $line", "Invalid line", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
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

            val (absolutePath, pathError) = PathValidator.resolveAndValidate(filePath, project.basePath)
            if (pathError != null) return pathError

            val position = withContext(Dispatchers.EDT) {
                val vFile = LocalFileSystem.getInstance().findFileByPath(absolutePath!!)
                    ?: return@withContext null
                XDebuggerUtil.getInstance().createPosition(vFile, line - 1)
            } ?: return ToolResult("File not found: $absolutePath", "File not found", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

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
            ToolResult("Error running to cursor: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

    // ── stop ────────────────────────────────────────────────────────────────

    private suspend fun executeStop(params: JsonObject, project: Project): ToolResult {
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
            ToolResult("Error stopping debug session: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

    // ── Private helpers ─────────────────────────────────────────────────────

    private fun missingParam(name: String): ToolResult = ToolResult(
        content = "Error: '$name' parameter required",
        summary = "Error: missing $name",
        tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
        isError = true
    )
}

package com.workflow.orchestrator.agent.tools.debug

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebuggerUtil
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.core.ai.TokenEstimator
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.builtin.PathValidator
import com.workflow.orchestrator.agent.tools.integration.ToolValidation
import com.workflow.orchestrator.agent.tools.platform.DebugState
import com.workflow.orchestrator.agent.tools.platform.IdeStateProbe
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
 * 10 actions with only 4 parameters (action, session_id, file, line).
 * Includes force_step_into (bypasses step filters for framework code)
 * and force_step_over (ignores breakpoints in called methods).
 */
class DebugStepTool(private val controller: AgentDebugController) : AgentTool {

    override val name = "debug_step"

    override val description = """
Debug session navigation — stepping, state, and lifecycle control.

IMPORTANT: Call get_state first to confirm session exists and whether it is paused.
Pause-required actions fail loudly when the session is running.

State tags: [SUSPENDED] = session must be paused. [ANY] = runs regardless.

Actions:
- get_state(session_id?) [ANY] → Current session state (paused/running), breakpoint, thread, line. CALL THIS FIRST.
- step_over(session_id?) [SUSPENDED] → Step over current line
- step_into(session_id?) [SUSPENDED] → Step into method call
- step_out(session_id?) [SUSPENDED] → Step out of current method
- force_step_into(session_id?) [SUSPENDED] → Step into even library/framework code (bypasses step filters — use to enter Spring proxies, CGLIB, reflection, or Kotlin inlined bodies)
- force_step_over(session_id?) [SUSPENDED] → Step over, ignoring any breakpoints in called methods
- run_to_cursor(file, line, session_id?) [SUSPENDED] → Run to specific line (despite the name, requires current suspension)
- resume(session_id?) [ANY] → Resume execution
- pause(session_id?) [ANY] → Best-effort pause. May take several seconds or fail if the JVM is in a non-suspendable state (native code, GC). Follow with get_state to confirm.
- stop(session_id?) [ANY] → Stop debug session

All actions accept optional session_id (defaults to active session).
""".trimIndent()

    override val parameters = FunctionParameters(
        properties = mapOf(
            "action" to ParameterProperty(
                type = "string",
                description = "Operation to perform",
                enumValues = listOf(
                    "get_state", "step_over", "step_into", "step_out",
                    "force_step_into", "force_step_over",
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
            "step_over" -> executeStepAction(params, project, "step_over") { it.stepOver(false) }
            "step_into" -> executeStepAction(params, project, "step_into") { it.stepInto() }
            "step_out" -> executeStepAction(params, project, "step_out") { it.stepOut() }
            "force_step_into" -> executeStepAction(params, project, "force_step_into") { it.forceStepInto() }
            "force_step_over" -> executeStepAction(params, project, "force_step_over") { it.stepOver(true) }
            "resume" -> executeResume(params, project)
            "pause" -> executePause(params, project)
            "run_to_cursor" -> executeRunToCursor(params, project)
            "stop" -> executeStop(params, project)
            else -> ToolResult(
                content = "Unknown action '$action'. Valid actions: get_state, step_over, step_into, step_out, force_step_into, force_step_over, resume, pause, run_to_cursor, stop",
                summary = "Unknown action '$action'",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }
    }

    // ── session resolution ──────────────────────────────────────────────────
    //
    // All actions resolve their target debug session through one of these two
    // helpers. Both delegate to IdeStateProbe so that sessions started outside
    // the agent (gutter Debug button, run config dropdown, etc.) are found via
    // the IntelliJ Platform — not just the agent's own session registry.
    //
    // The agent's controller registry is still consulted first via the
    // registryLookup callback, so sessions started by start_debug_session keep
    // their agent-assigned ids and `activeSessionId` semantics.

    private sealed class SessionResolution {
        data class Found(val session: XDebugSession) : SessionResolution()
        data class Failed(val toolResult: ToolResult) : SessionResolution()
    }

    /**
     * Resolves a debug session that exists (running or paused) for [sessionId].
     * Use this for actions that don't require the session to be paused
     * (resume, pause, stop, get_state).
     */
    private fun requireSession(project: Project, sessionId: String?): SessionResolution {
        val state = IdeStateProbe.debugState(project, sessionId, controller::getSession)
        return when (state) {
            is DebugState.Paused -> SessionResolution.Found(state.session)
            is DebugState.Running -> SessionResolution.Found(state.session)
            DebugState.NoSession -> SessionResolution.Failed(noSessionError(sessionId))
            is DebugState.AmbiguousSession -> SessionResolution.Failed(ambiguousError(state))
        }
    }

    /**
     * Resolves a debug session that exists AND is currently paused for [sessionId].
     * Use this for actions that need the session to be suspended
     * (step_over, step_into, step_out, force_step_into, force_step_over, run_to_cursor).
     */
    private fun requireSuspendedSession(project: Project, sessionId: String?): SessionResolution {
        val state = IdeStateProbe.debugState(project, sessionId, controller::getSession)
        return when (state) {
            is DebugState.Paused -> SessionResolution.Found(state.session)
            is DebugState.Running -> SessionResolution.Failed(notSuspendedError())
            DebugState.NoSession -> SessionResolution.Failed(noSessionError(sessionId))
            is DebugState.AmbiguousSession -> SessionResolution.Failed(ambiguousError(state))
        }
    }

    private fun noSessionError(sessionId: String?) = ToolResult(
        buildString {
            append("No debug session found")
            if (sessionId != null) append(": $sessionId")
            append(". Start one with start_debug_session, or have the user start a debug session via the IDE (gutter Debug button or Run menu) and try again.")
        },
        "No session",
        ToolResult.ERROR_TOKEN_ESTIMATE,
        isError = true,
    )

    private fun notSuspendedError() = ToolResult(
        "No suspended session resolved. Common causes: " +
            "(1) multiple sessions are open and the one you targeted is running — " +
            "pass session_id explicitly, since `currentSession` resolves to the last-focused session (not necessarily the paused one); " +
            "(2) the program isn't at a breakpoint yet — set one and let execution reach it, or call debug_step(action=pause). " +
            "Run debug_step(action=get_state) first to list sessions and their paused/running state.",
        "Not suspended",
        ToolResult.ERROR_TOKEN_ESTIMATE,
        isError = true,
    )

    private fun ambiguousError(state: DebugState.AmbiguousSession) = ToolResult(
        "Multiple debug sessions are active (${state.count}: ${state.names.joinToString(", ")}). Pass session_id to disambiguate.",
        "Ambiguous session",
        ToolResult.ERROR_TOKEN_ESTIMATE,
        isError = true,
    )

    // ── get_state ───────────────────────────────────────────────────────────

    private suspend fun executeGetState(params: JsonObject, project: Project): ToolResult {
        val sessionId = params["session_id"]?.jsonPrimitive?.content

        val session = when (val r = requireSession(project, sessionId)) {
            is SessionResolution.Found -> r.session
            is SessionResolution.Failed -> return r.toolResult
        }

        return try {
            val sb = StringBuilder()
            sb.append("Session: ${session.sessionName}\n")

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
        project: Project,
        actionName: String,
        action: (XDebugSession) -> Unit
    ): ToolResult {
        val sessionId = params["session_id"]?.jsonPrimitive?.content
        return executeStep(controller, project, sessionId, actionName, action)
    }

    // ── resume ──────────────────────────────────────────────────────────────

    private suspend fun executeResume(params: JsonObject, project: Project): ToolResult {
        val sessionId = params["session_id"]?.jsonPrimitive?.content

        val session = when (val r = requireSession(project, sessionId)) {
            is SessionResolution.Found -> r.session
            is SessionResolution.Failed -> return r.toolResult
        }

        return try {
            withContext(Dispatchers.EDT) { session.resume() }
            val content = "Session resumed. Session: ${session.sessionName}"
            ToolResult(content, "Session resumed", TokenEstimator.estimate(content))
        } catch (e: Exception) {
            ToolResult("Error resuming debug session: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

    // ── pause ───────────────────────────────────────────────────────────────

    private suspend fun executePause(params: JsonObject, project: Project): ToolResult {
        val sessionId = params["session_id"]?.jsonPrimitive?.content

        val session = when (val r = requireSession(project, sessionId)) {
            is SessionResolution.Found -> r.session
            is SessionResolution.Failed -> return r.toolResult
        }

        return try {
            withContext(Dispatchers.EDT) { session.pause() }

            val name = session.sessionName
            // Try to get a registered ID for waitForPause; fall back to session name
            val registeredId = sessionId ?: controller.getActiveSessionId() ?: name
            val pauseEvent = controller.waitForPause(registeredId, 5000)

            val content = if (pauseEvent != null) {
                "Session paused at ${pauseEvent.file ?: "unknown"}:${pauseEvent.line ?: "?"}\nSession: $name"
            } else {
                "Pause requested. Session: $name"
            }

            ToolResult(content, "Pause requested", TokenEstimator.estimate(content))
        } catch (e: Exception) {
            ToolResult("Error pausing debug session: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

    // ── run_to_cursor ───────────────────────────────────────────────────────

    private suspend fun executeRunToCursor(params: JsonObject, project: Project): ToolResult {
        val filePath = params["file"]?.jsonPrimitive?.content ?: return ToolValidation.missingParam("file")
        val line = params["line"]?.jsonPrimitive?.intOrNull ?: return ToolValidation.missingParam("line")
        val sessionId = params["session_id"]?.jsonPrimitive?.content

        if (line < 1) {
            return ToolResult("Line number must be >= 1, got: $line", "Invalid line", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }

        val session = when (val r = requireSuspendedSession(project, sessionId)) {
            is SessionResolution.Found -> r.session
            is SessionResolution.Failed -> return r.toolResult
        }

        return try {
            val (absolutePath, pathError) = PathValidator.resolveAndValidate(filePath, project.basePath)
            if (pathError != null) return pathError

            val position = withContext(Dispatchers.EDT) {
                val vFile = LocalFileSystem.getInstance().findFileByPath(absolutePath!!)
                    ?: return@withContext null
                XDebuggerUtil.getInstance().createPosition(vFile, line - 1)
            } ?: return ToolResult("File not found: $absolutePath", "File not found", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

            withContext(Dispatchers.EDT) { session.runToPosition(position, false) }

            val name = session.sessionName
            // Try to get a registered ID for waitForPause; fall back to session name
            val registeredId = sessionId ?: controller.getActiveSessionId() ?: name
            val pauseEvent = controller.waitForPause(registeredId, 30000)

            val content = if (pauseEvent != null) {
                "Reached ${pauseEvent.file ?: "unknown"}:${pauseEvent.line ?: "?"}\nSession: $name"
            } else {
                "Run to cursor requested ($filePath:$line). Session did not pause within 30s.\nSession: $name"
            }

            ToolResult(content, "Run to cursor", TokenEstimator.estimate(content))
        } catch (e: Exception) {
            ToolResult("Error running to cursor: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

    // ── stop ────────────────────────────────────────────────────────────────

    private suspend fun executeStop(params: JsonObject, project: Project): ToolResult {
        val sessionId = params["session_id"]?.jsonPrimitive?.content

        val session = when (val r = requireSession(project, sessionId)) {
            is SessionResolution.Found -> r.session
            is SessionResolution.Failed -> return r.toolResult
        }

        return try {
            withContext(Dispatchers.EDT) { session.stop() }
            val content = "Debug session stopped. Session: ${session.sessionName}"
            ToolResult(content, "Debug session stopped", TokenEstimator.estimate(content))
        } catch (e: Exception) {
            ToolResult("Error stopping debug session: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

}

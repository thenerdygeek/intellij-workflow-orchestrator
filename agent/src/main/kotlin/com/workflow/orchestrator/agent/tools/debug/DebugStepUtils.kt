package com.workflow.orchestrator.agent.tools.debug

import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XDebugSession
import com.workflow.orchestrator.core.ai.TokenEstimator
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.platform.DebugState
import com.workflow.orchestrator.agent.tools.platform.IdeStateProbe

/**
 * Shared execution logic for step-over, step-into, and step-out tools.
 * Validates session state, performs the step action, waits for pause,
 * and auto-includes top-frame variables to save the agent a round-trip.
 *
 * Uses [IdeStateProbe.debugState] so that user-started debug sessions
 * (gutter Debug button, run config dropdown, etc.) are found via the
 * IntelliJ Platform — not just the agent's own session registry.
 */
internal suspend fun executeStep(
    controller: AgentDebugController,
    project: Project,
    sessionId: String?,
    actionName: String,
    action: (XDebugSession) -> Unit
): ToolResult {
    return try {
        val state = IdeStateProbe.debugState(project, sessionId, controller::getSession)
        val session = when (state) {
            is DebugState.Paused -> state.session
            is DebugState.Running -> return ToolResult(
                "Debug session is running but not paused. Cannot $actionName while running. Set a breakpoint and let execution reach it, or call debug_step.pause first.",
                "Not suspended",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
            DebugState.NoSession -> return ToolResult(
                buildString {
                    append("No debug session found")
                    if (sessionId != null) append(": $sessionId")
                    append(". Start one with start_debug_session, or have the user start a debug session via the IDE (gutter Debug button or Run menu) and try again.")
                },
                "No session",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
            is DebugState.AmbiguousSession -> return ToolResult(
                "Multiple debug sessions are active (${state.count}: ${state.names.joinToString(", ")}). Pass session_id to disambiguate.",
                "Ambiguous session",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }

        // Perform the step action
        action(session)

        // Wait for the step to complete (steps are near-instant)
        val name = session.sessionName
        // Try to get a registered ID for waitForPause; fall back to session name
        val registeredId = sessionId ?: controller.getActiveSessionId() ?: name
        val pauseEvent = controller.waitForPause(registeredId, 5000)

        // Build output with position and variables
        val sb = StringBuilder()
        if (pauseEvent != null) {
            sb.append("Stepped to ${pauseEvent.file ?: "unknown"}:${pauseEvent.line ?: "?"}\n")
        } else {
            sb.append("Step completed but session did not pause within 5s (may have hit end of execution)\n")
        }
        sb.append("Session: $name\n")

        // Auto-include top-frame variables (depth 1) to save a round-trip
        val frame = session.currentStackFrame
        if (frame != null) {
            val variables = controller.getVariables(frame, 1)
            if (variables.isNotEmpty()) {
                sb.append("\nVariables:\n")
                sb.append(formatVariables(variables))
            }
        }

        val content = sb.toString()
        ToolResult(content, "Step $actionName completed", TokenEstimator.estimate(content))
    } catch (e: Exception) {
        ToolResult(
            "Error during $actionName: ${e.message}",
            "Error",
            ToolResult.ERROR_TOKEN_ESTIMATE,
            isError = true
        )
    }
}

/**
 * Formats variable info into a readable string with indentation for nested children.
 * Truncation-sentinel entries (v.truncated == true) are rendered as "… <value>"
 * so the LLM can see the list is partial without the usual "name: type = value" layout.
 */
internal fun formatVariables(vars: List<VariableInfo>, indent: String = "  "): String {
    val sb = StringBuilder()
    for (v in vars) {
        if (v.truncated) {
            sb.append(indent).append("… ").append(v.value).append('\n')
            continue
        }
        val children = if (v.children.isNotEmpty()) "\n" + formatVariables(v.children, "$indent  ") else ""
        sb.append("${indent}${v.name}: ${v.type} = ${v.value}$children\n")
    }
    // Remove the trailing newline to preserve the original contract (joinToString has no trailing \n)
    return sb.trimEnd('\n').toString()
}

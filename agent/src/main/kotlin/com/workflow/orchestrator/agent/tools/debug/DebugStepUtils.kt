package com.workflow.orchestrator.agent.tools.debug

import com.intellij.xdebugger.XDebugSession
import com.workflow.orchestrator.agent.context.TokenEstimator
import com.workflow.orchestrator.agent.tools.ToolResult

/**
 * Shared execution logic for step-over, step-into, and step-out tools.
 * Validates session state, performs the step action, waits for pause,
 * and auto-includes top-frame variables to save the agent a round-trip.
 */
internal suspend fun executeStep(
    controller: AgentDebugController,
    sessionId: String?,
    actionName: String,
    action: (XDebugSession) -> Unit
): ToolResult {
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
                "Session is not suspended. Cannot $actionName while running.",
                "Not suspended",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }

        // Perform the step action
        action(session)

        // Wait for the step to complete (steps are near-instant)
        val id = resolvedId ?: "unknown"
        val pauseEvent = controller.waitForPause(id, 5000)

        // Build output with position and variables
        val sb = StringBuilder()
        if (pauseEvent != null) {
            sb.append("Stepped to ${pauseEvent.file ?: "unknown"}:${pauseEvent.line ?: "?"}\n")
        } else {
            sb.append("Step completed but session did not pause within 5s (may have hit end of execution)\n")
        }
        sb.append("Session: $id\n")

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
 */
internal fun formatVariables(vars: List<VariableInfo>, indent: String = "  "): String {
    return vars.joinToString("\n") { v ->
        val children = if (v.children.isNotEmpty()) "\n" + formatVariables(v.children, "$indent  ") else ""
        "${indent}${v.name}: ${v.type} = ${v.value}$children"
    }
}

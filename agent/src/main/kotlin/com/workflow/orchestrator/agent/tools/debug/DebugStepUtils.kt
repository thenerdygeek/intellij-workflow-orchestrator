package com.workflow.orchestrator.agent.tools.debug

import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XDebugSession
import com.workflow.orchestrator.core.ai.TokenEstimator
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.platform.DebugState
import com.workflow.orchestrator.agent.tools.platform.IdeStateProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

        // Perform the step action — XDebugSession step methods require EDT.
        withContext(Dispatchers.EDT) { action(session) }

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
 *
 * @param includeInternals when false (default), CGLIB / Spring-proxy synthetic fields are
 *   filtered out — these are runtime-generated wrappers that just add noise to the LLM's
 *   view of a Spring bean's state. Counted and surfaced as a "(+N internal field(s) hidden)"
 *   footer so the LLM knows they exist.
 */
internal fun formatVariables(
    vars: List<VariableInfo>,
    indent: String = "  ",
    includeInternals: Boolean = false,
): String {
    val visible = mutableListOf<VariableInfo>()
    var hiddenCount = 0
    for (v in vars) {
        if (!includeInternals && isProxyInternalField(v.name)) {
            hiddenCount++
        } else {
            visible.add(v)
        }
    }
    val sb = StringBuilder()
    for (v in visible) {
        if (v.truncated) {
            sb.append(indent).append("… ").append(v.value).append('\n')
            continue
        }
        val children = if (v.children.isNotEmpty())
            "\n" + formatVariables(v.children, "$indent  ", includeInternals)
        else ""
        sb.append("${indent}${v.name}: ${v.type} = ${v.value}$children\n")
    }
    if (hiddenCount > 0) {
        sb.append(indent).append("(+$hiddenCount internal field(s) hidden — CGLIB/proxy synthetics; ")
            .append("pass `include_internals=true` to see them)\n")
    }
    // Remove the trailing newline to preserve the original contract (joinToString has no trailing \n)
    return sb.trimEnd('\n').toString()
}

/**
 * True when [name] is a synthetic field generated by CGLIB / Spring proxies. These show up
 * at the top of every proxied bean and crowd out the real instance state the agent actually
 * wants to read. We match only patterns that are unambiguously bytecode-generated:
 *   - `CGLIB$*` — CGLIB's own marker fields (BOUND, FACTORY_DATA, THREAD_CALLBACKS, etc.)
 *   - `$$$*`   — other JDK/bytecode synthetics
 *
 * Earlier drafts also filtered `definition` / `profiles`, but those are legitimate field
 * names in non-Spring code (e.g. `Article.definition`, `UserProfile.profiles`). Silent data
 * loss would be a correctness regression — review caught this. We keep the filter strictly
 * to the unambiguous proxy patterns.
 */
internal fun isProxyInternalField(name: String): Boolean {
    if (name.startsWith("CGLIB\$")) return true
    if (name.startsWith("$\$\$")) return true   // some bytecode-generated proxy markers
    // Anonymous synthetic fields like "this$0" / "this$1" — keep these; they're useful
    // for inner-class debugging. Don't filter.
    return false
}

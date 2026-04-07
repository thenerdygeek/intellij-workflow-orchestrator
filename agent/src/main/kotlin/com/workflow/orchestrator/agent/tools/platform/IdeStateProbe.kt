package com.workflow.orchestrator.agent.tools.platform

import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebuggerManager

/**
 * Centralized, platform-truth queries for IntelliJ IDE state.
 *
 * Tools must use this rather than maintaining their own world models or caches.
 * The first responsibility of any IDE-touching tool is "is the IDE in the state
 * I need" — and that question must be answered against the IntelliJ Platform
 * itself, not against an agent-side registry that may be stale or unaware of
 * state changes initiated by the user.
 *
 * Background: prior to this layer, the debug-related tools consulted an
 * agent-owned session registry ([com.workflow.orchestrator.agent.tools.debug.AgentDebugController])
 * that only knew about sessions started via the agent's own `start_debug_session`
 * tool. When the user started a debug session via the editor gutter or run
 * config dropdown, those tools incorrectly reported "no debug session" even
 * while the debugger was clearly paused at a breakpoint.
 *
 * [IdeStateProbe] always queries [XDebuggerManager] directly, then optionally
 * consults a registry callback to disambiguate when multiple sessions exist
 * and the agent has its own ids.
 */
object IdeStateProbe {

    /**
     * Resolves the debug state for [project], honoring an optional [sessionId].
     *
     * Resolution order:
     *   1. If [sessionId] is provided and [registryLookup] returns a session for it, use that.
     *   2. Otherwise, if [sessionId] matches a platform session by `sessionName`, use that.
     *   3. Otherwise, use [XDebuggerManager.currentSession] (the user-focused session).
     *   4. Otherwise, if exactly one platform session exists, use it.
     *   5. Otherwise: [DebugState.AmbiguousSession] (multiple, no disambiguator) or
     *      [DebugState.NoSession] (none).
     *
     * @param project        the project to query
     * @param sessionId      optional session identifier (agent-assigned id or platform sessionName)
     * @param registryLookup optional callback for resolving an agent-assigned id to an
     *                       [XDebugSession]. Pass `controller::getSession` from the agent
     *                       debug tools so registered sessions still resolve via the registry.
     */
    fun debugState(
        project: Project,
        sessionId: String? = null,
        registryLookup: ((String) -> XDebugSession?)? = null,
    ): DebugState {
        val mgr = XDebuggerManager.getInstance(project)
        val all = mgr.debugSessions.toList()

        val target: XDebugSession? = when {
            sessionId != null -> {
                registryLookup?.invoke(sessionId)
                    ?: all.firstOrNull { it.sessionName == sessionId }
            }
            mgr.currentSession != null -> mgr.currentSession
            all.size == 1 -> all.single()
            else -> null
        }

        return when {
            target != null && target.isSuspended -> DebugState.Paused(target)
            target != null -> DebugState.Running(target)
            all.isEmpty() -> DebugState.NoSession
            else -> DebugState.AmbiguousSession(all.size, all.map { it.sessionName })
        }
    }
}

/** Coarse debug state for a project, as observed via the IntelliJ Platform. */
sealed class DebugState {
    /** No debug session exists in this project. */
    object NoSession : DebugState()

    /** Exactly one resolvable session, currently running (not paused). */
    data class Running(val session: XDebugSession) : DebugState()

    /** Exactly one resolvable session, currently paused (at a breakpoint or after a step). */
    data class Paused(val session: XDebugSession) : DebugState()

    /**
     * Multiple sessions exist and no [sessionId] was provided to disambiguate.
     * The caller should ask the user (or the agent should pass a session id).
     */
    data class AmbiguousSession(val count: Int, val names: List<String>) : DebugState()
}

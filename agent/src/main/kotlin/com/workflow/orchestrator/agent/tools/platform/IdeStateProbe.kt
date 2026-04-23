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
     *   1. If [sessionId] is provided and [registryLookup] returns a session for it, use that
     *      (authoritative UUID / agent-handle path — never ambiguous).
     *   2. Otherwise, if [sessionId] matches exactly one platform session by `sessionName`, use it
     *      (preserved string-match fallback — for user-started sessions where the LLM types the
     *      session's display name).
     *   3. If [sessionId] matches MULTIPLE platform sessions by `sessionName` (post-restart
     *      duplicates, two `gradle:test` runs of the same config, etc.) the request is ambiguous —
     *      return [DebugState.AmbiguousSession] so the caller can surface it instead of silently
     *      picking the wrong one. Task 4.5 fix.
     *   4. Otherwise, use [XDebuggerManager.currentSession] (the user-focused session).
     *   5. Otherwise, if exactly one platform session exists, use it.
     *   6. Otherwise: [DebugState.AmbiguousSession] (multiple, no disambiguator) or
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

        // Canonical paused check per XDebugSession.java (L51-L69): isSuspended
        // alone is insufficient because the flag flips before the engine populates
        // currentStackFrame/suspendContext. All three clauses must hold — matching
        // what XDebuggerEvaluator, XValueContainer.computeChildren, and
        // XExecutionStack.computeStackFrames themselves require before accepting
        // work. Audit finding C1/C5.
        fun isPaused(s: XDebugSession): Boolean =
            s.isSuspended && s.currentStackFrame != null && s.suspendContext != null

        // sessionId path is handled separately so we can return AmbiguousSession mid-resolution
        // when the string-match fallback finds >1 platform session sharing a display name.
        // Without this, `sessionId == "MyApp"` silently resolves to `all.first()` even when two
        // post-restart duplicates exist — the exact correctness bug Task 4.5 fixes.
        if (sessionId != null) {
            val fromRegistry = registryLookup?.invoke(sessionId)
            if (fromRegistry != null) {
                // UUID / agent-handle match — authoritative regardless of sessionName collisions.
                return if (isPaused(fromRegistry)) DebugState.Paused(fromRegistry)
                else DebugState.Running(fromRegistry)
            }
            val byName = all.filter { it.sessionName == sessionId }
            when (byName.size) {
                0 -> {
                    // Fall through to the currentSession / single-session / ambiguous / none cascade
                    // below so an unknown sessionId still yields a meaningful state when the caller
                    // passed one speculatively (e.g. from a stale tool result). This matches the
                    // pre-Task-4.5 behavior for the "unknown id" case.
                }
                1 -> {
                    val unique = byName.single()
                    return if (isPaused(unique)) DebugState.Paused(unique) else DebugState.Running(unique)
                }
                else -> return DebugState.AmbiguousSession(byName.size, byName.map { it.sessionName })
            }
        }

        // Prefer a uniquely-paused session over currentSession. currentSession
        // returns the last-focused session, which is often *not* the one at a
        // breakpoint when multiple sessions are open — the exact cause of the
        // "session is running but not paused" false negative in the 2026-04-23
        // audit (finding C1).
        val pausedSessions = all.filter(::isPaused)
        val target: XDebugSession? = when {
            pausedSessions.size == 1 -> pausedSessions.single()
            mgr.currentSession != null -> mgr.currentSession
            all.size == 1 -> all.single()
            else -> null
        }

        return when {
            target != null && isPaused(target) -> DebugState.Paused(target)
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

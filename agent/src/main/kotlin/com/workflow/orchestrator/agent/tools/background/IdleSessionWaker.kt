package com.workflow.orchestrator.agent.tools.background

/** Outcome of routing a nudge to an idle (no active loop) session. */
enum class IdleWakeRoute { WAKE, DEFER_NO_LISTENER, SKIP_GUARD, DEFER_ACTIVE_SESSION }

/**
 * Pure routing decision for an async completion (background process OR cross-IDE
 * delegation result/question) that arrives when a session's loop has already ended.
 *
 * - NOT safe to resume (a DIFFERENT session is actively running) → [IdleWakeRoute.DEFER_ACTIVE_SESSION]
 * - guard rejected (`!= PROCEED`)                                → [IdleWakeRoute.SKIP_GUARD]
 * - no UI listener wired                                         → [IdleWakeRoute.DEFER_NO_LISTENER]
 * - otherwise                                                    → [IdleWakeRoute.WAKE]
 *
 * BUG #4 — the `safeToResume` gate is evaluated FIRST so an auto-wake for an idle target
 * never tears down a different live session (`resumeSession` → `prepareForReplay` cancels
 * the current job + resets the chat). It is checked before the guard so a deferred wake
 * does NOT consume the per-session cap/cooldown — the nudge is left persisted and replays
 * verbatim when the user next resumes that target session.
 */
fun idleWakeRoute(
    wakeDecision: AutoWakeGuardState.Decision,
    listenerPresent: Boolean,
    safeToResume: Boolean,
): IdleWakeRoute = when {
    !safeToResume -> IdleWakeRoute.DEFER_ACTIVE_SESSION
    wakeDecision != AutoWakeGuardState.Decision.PROCEED -> IdleWakeRoute.SKIP_GUARD
    !listenerPresent -> IdleWakeRoute.DEFER_NO_LISTENER
    else -> IdleWakeRoute.WAKE
}

/** Snapshot of the auto-wake settings read per decision. */
data class AutoWakeSettings(val enabled: Boolean, val cap: Int, val cooldownMs: Long)

/**
 * Orchestrates a guarded auto-wake of an idle session. Shared by background-process
 * completion and cross-IDE delegation result/question delivery so both async-completion
 * paths behave identically: compute the guard decision, route via [idleWakeRoute], and on
 * [IdleWakeRoute.WAKE] hand the synthetic message to the registered listener through
 * [invoker] (production: `invokeLater` on the EDT).
 *
 * Extracted from `AgentService` so the wiring is unit-testable without constructing the
 * full service (whose init loads the entire tool / memory / hook subsystem). All
 * dependencies are injected; [guards] MUST be the same shared instance both call sites use
 * so the per-session cap and cooldown are honoured across background + delegation wakes.
 */
class IdleSessionWaker(
    private val guards: AutoWakeGuardState,
    private val settings: () -> AutoWakeSettings,
    private val listener: () -> ((String, String) -> Unit)?,
    /**
     * BUG #4 — the id of the session the user is currently on (the live [AgentLoop]'s
     * session), or null when no task is active. An auto-wake-resume is only SAFE when the
     * target equals this (or there is no active session); otherwise resuming the target
     * would cancel/reset the live different session. Defaults to `{ null }` so existing
     * callers/tests that never run two sessions keep the unconditional-wake behaviour.
     */
    private val activeSessionId: () -> String? = { null },
    private val invoker: (() -> Unit) -> Unit = { it() },
    private val onLog: (String) -> Unit = {},
) {
    /** Returns the route taken (for callers/tests). */
    fun wake(sessionId: String, syntheticText: String, source: String): IdleWakeRoute {
        // BUG #4 — evaluate safety FIRST, before consulting the guard, so a deferred wake
        // for a non-active target does not consume the per-session cap/cooldown. The nudge
        // is left persisted at the call site and replays when the target is next resumed.
        val active = activeSessionId()
        val safeToResume = active == null || active == sessionId
        if (!safeToResume) {
            onLog(
                "[AutoWake] deferred — session=$sessionId is not the active session ($active); " +
                    "leaving persisted for replay; source=$source"
            )
            return IdleWakeRoute.DEFER_ACTIVE_SESSION
        }

        val s = settings()
        val decision = guards.decide(sessionId, s.enabled, s.cap, s.cooldownMs)
        val route = idleWakeRoute(decision, listenerPresent = listener() != null, safeToResume = true)
        when (route) {
            IdleWakeRoute.WAKE -> {
                val l = listener() ?: return IdleWakeRoute.DEFER_NO_LISTENER
                invoker {
                    runCatching { l(sessionId, syntheticText) }
                        .onFailure { onLog("[AutoWake] listener failed: ${it.message}") }
                }
            }
            IdleWakeRoute.DEFER_NO_LISTENER ->
                onLog("[AutoWake] no listener registered; deferring to resume pickup; session=$sessionId source=$source")
            IdleWakeRoute.SKIP_GUARD ->
                onLog(
                    "[AutoWake] skipped (${decision.name}); session=$sessionId source=$source " +
                        "attempts=${guards.attemptCount(sessionId)}"
                )
            IdleWakeRoute.DEFER_ACTIVE_SESSION -> {} // unreachable: handled by the early return above
        }
        return route
    }
}

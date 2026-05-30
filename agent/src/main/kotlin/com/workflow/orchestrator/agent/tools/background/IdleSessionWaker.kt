package com.workflow.orchestrator.agent.tools.background

/** Outcome of routing a nudge to an idle (no active loop) session. */
enum class IdleWakeRoute { WAKE, DEFER_NO_LISTENER, SKIP_GUARD }

/**
 * Pure routing decision for an async completion (background process OR cross-IDE
 * delegation result/question) that arrives when a session's loop has already ended.
 *
 * - guard rejected (`!= PROCEED`) → [IdleWakeRoute.SKIP_GUARD]
 * - no UI listener wired         → [IdleWakeRoute.DEFER_NO_LISTENER]
 * - otherwise                    → [IdleWakeRoute.WAKE]
 */
fun idleWakeRoute(
    wakeDecision: AutoWakeGuardState.Decision,
    listenerPresent: Boolean,
): IdleWakeRoute = when {
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
    private val invoker: (() -> Unit) -> Unit = { it() },
    private val onLog: (String) -> Unit = {},
) {
    /** Returns the route taken (for callers/tests). */
    fun wake(sessionId: String, syntheticText: String, source: String): IdleWakeRoute {
        val s = settings()
        val decision = guards.decide(sessionId, s.enabled, s.cap, s.cooldownMs)
        val route = idleWakeRoute(decision, listenerPresent = listener() != null)
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
        }
        return route
    }
}

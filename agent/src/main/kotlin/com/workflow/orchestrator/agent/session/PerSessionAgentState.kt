package com.workflow.orchestrator.agent.session

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Per-session in-memory mutable state for the agent.
 *
 * Holds state that must NOT be shared across concurrent sessions in the same
 * project — most importantly the plan-mode toggle. Created lazily by
 * AgentService when a session is first touched, removed when the session ends.
 *
 * Persistence: only the on-disk Session.planModeEnabled is the source of truth
 * across restarts. This in-memory holder is loaded from there on session
 * resume and written back when the value changes.
 */
class PerSessionAgentState(
    val sessionId: String,
) {
    /**
     * Plan-mode toggle for this session. Always starts false; callers that need to
     * seed a specific value (e.g. from the persisted HistoryItem on resume) must call
     * `.planModeActive.set(value)` explicitly after construction.
     *
     * Removing the `initialPlanMode` constructor parameter prevents the subtle bug
     * where `computeIfAbsent` silently returns the existing instance and ignores the
     * requested initial value on the second call (F5 fix).
     */
    val planModeActive: AtomicBoolean = AtomicBoolean(false)
}

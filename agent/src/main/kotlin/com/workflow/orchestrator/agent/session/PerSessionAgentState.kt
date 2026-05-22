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
    initialPlanMode: Boolean = false,
) {
    val planModeActive: AtomicBoolean = AtomicBoolean(initialPlanMode)
}

package com.workflow.orchestrator.agent.session

import java.util.concurrent.atomic.AtomicBoolean

// DelegationMetadata import is intentionally from the agent session package —
// the same DelegationMetadata that AgentService uses when starting a delegated session.

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

    /**
     * Delegation metadata for this session if it was started via cross-IDE delegation
     * (i.e. an IDE-A sent a Connect message and IDE-B accepted it). Null for locally-
     * initiated sessions.
     *
     * Set by [com.workflow.orchestrator.agent.AgentService.startDelegatedSession]
     * before the agent loop is launched so that tools (e.g. [com.workflow.orchestrator
     * .agent.tools.builtin.AskQuestionsTool]) can detect the delegated context and
     * route accordingly.
     *
     * Plan 2 Task 4 — Spec §6.3.
     */
    @Volatile
    var delegated: DelegationMetadata? = null
}

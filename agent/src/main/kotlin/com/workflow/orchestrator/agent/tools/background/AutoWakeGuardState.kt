package com.workflow.orchestrator.agent.tools.background

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Task 6.1 — per-session guardrail state for auto-waking a session on background
 * completion events.
 *
 * The guard lives outside [com.workflow.orchestrator.agent.AgentService] so it can
 * be unit-tested in isolation. `AgentService` holds exactly one instance and
 * consults it from inside [com.workflow.orchestrator.agent.AgentService.onBackgroundCompletion]
 * before firing the auto-wake path.
 *
 * Guardrails enforced:
 *  - [Decision.DISABLED]    — master toggle (AgentSettings.autoWakeOnBackgroundCompletion) is off
 *  - [Decision.CAP_REACHED] — the per-session attempt count has reached the configured cap
 *  - [Decision.COOLDOWN]    — the most recent auto-wake for this session is within cooldownMs
 *  - [Decision.PROCEED]     — all checks passed; counter is incremented and timestamp updated
 *
 * Note: iteration-budget and session-lock guards are deferred — see the
 * `// TODO: iteration budget guard` note in AgentService.autoResumeForBackgroundCompletion.
 */
class AutoWakeGuardState {

    private val counts = ConcurrentHashMap<String, AtomicInteger>()
    private val lastAt = ConcurrentHashMap<String, AtomicLong>()

    enum class Decision { PROCEED, DISABLED, CAP_REACHED, COOLDOWN }

    /**
     * Atomic decision + counter update. Callers should fire the auto-wake side
     * effect iff the result is [Decision.PROCEED].
     */
    fun decide(
        sessionId: String,
        enabled: Boolean,
        cap: Int,
        cooldownMs: Long,
        now: Long = System.currentTimeMillis(),
    ): Decision {
        if (!enabled) return Decision.DISABLED
        val count = counts.computeIfAbsent(sessionId) { AtomicInteger(0) }
        if (count.get() >= cap) return Decision.CAP_REACHED
        val last = lastAt.computeIfAbsent(sessionId) { AtomicLong(0) }
        val lastVal = last.get()
        // Cooldown only applies after an initial successful wake — if lastVal==0
        // there has never been one, so the cooldown window is not yet armed.
        if (lastVal > 0 && now - lastVal < cooldownMs) return Decision.COOLDOWN
        count.incrementAndGet()
        last.set(now)
        return Decision.PROCEED
    }

    /** Current auto-wake attempt count for [sessionId]. Test/observability helper. */
    fun attemptCount(sessionId: String): Int = counts[sessionId]?.get() ?: 0

    /** Clear all per-session counters. Called on new-chat / session reset so
     *  a long-lived project instance doesn't accumulate cap state across logical
     *  sessions that happen to reuse the same id (rare but defensible). */
    fun reset() {
        counts.clear()
        lastAt.clear()
    }

    /** Clear counters for a single session (e.g. when it is deleted). */
    fun resetSession(sessionId: String) {
        counts.remove(sessionId)
        lastAt.remove(sessionId)
    }
}

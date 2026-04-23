package com.workflow.orchestrator.agent.tools.background

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Task 6.1 — direct unit tests for the auto-wake guardrail helper. We test
 * [AutoWakeGuardState] in isolation (not through [com.workflow.orchestrator.agent.AgentService])
 * because `AgentService`'s init block loads tools, memory, hooks, etc. — infeasible
 * to mock. See the BackgroundCompletionSteeringTest note about the same constraint.
 */
class AutoWakeGuardStateTest {

    @Test
    fun `disabled returns DISABLED without incrementing the counter`() {
        val guard = AutoWakeGuardState()
        val result = guard.decide(
            sessionId = "sess-off",
            enabled = false,
            cap = 10,
            cooldownMs = 0L,
        )
        assertEquals(AutoWakeGuardState.Decision.DISABLED, result)
        assertEquals(0, guard.attemptCount("sess-off"),
            "DISABLED must not advance the counter")
    }

    @Test
    fun `cap returns CAP_REACHED once the configured max is reached`() {
        val guard = AutoWakeGuardState()
        val first = guard.decide("sess-cap", enabled = true, cap = 2, cooldownMs = 0L, now = 0L)
        val second = guard.decide("sess-cap", enabled = true, cap = 2, cooldownMs = 0L, now = 1L)
        val third = guard.decide("sess-cap", enabled = true, cap = 2, cooldownMs = 0L, now = 2L)

        assertEquals(AutoWakeGuardState.Decision.PROCEED, first)
        assertEquals(AutoWakeGuardState.Decision.PROCEED, second)
        assertEquals(AutoWakeGuardState.Decision.CAP_REACHED, third,
            "third decide call must be blocked by cap")
        assertEquals(2, guard.attemptCount("sess-cap"),
            "CAP_REACHED must not advance the counter past the cap")
    }

    @Test
    fun `cooldown returns COOLDOWN when the gap is too small`() {
        val guard = AutoWakeGuardState()
        val first = guard.decide("sess-cool", enabled = true, cap = 10, cooldownMs = 5_000L, now = 1_000L)
        val secondTooSoon = guard.decide("sess-cool", enabled = true, cap = 10, cooldownMs = 5_000L, now = 2_000L)

        assertEquals(AutoWakeGuardState.Decision.PROCEED, first)
        assertEquals(AutoWakeGuardState.Decision.COOLDOWN, secondTooSoon,
            "second call within cooldown must be skipped")
        assertEquals(1, guard.attemptCount("sess-cool"),
            "COOLDOWN must not advance the counter")
    }

    @Test
    fun `cooldown clears once enough time has passed`() {
        val guard = AutoWakeGuardState()
        guard.decide("sess-cool2", enabled = true, cap = 10, cooldownMs = 5_000L, now = 0L)
        val after = guard.decide("sess-cool2", enabled = true, cap = 10, cooldownMs = 5_000L, now = 5_000L)

        assertEquals(AutoWakeGuardState.Decision.PROCEED, after,
            "after cooldown window elapses, decide() must proceed")
        assertEquals(2, guard.attemptCount("sess-cool2"))
    }

    @Test
    fun `proceed increments counters and updates last timestamp`() {
        val guard = AutoWakeGuardState()
        val r = guard.decide("sess-ok", enabled = true, cap = 5, cooldownMs = 0L, now = 123L)
        assertEquals(AutoWakeGuardState.Decision.PROCEED, r)
        assertEquals(1, guard.attemptCount("sess-ok"))
    }

    @Test
    fun `different sessions are tracked independently`() {
        val guard = AutoWakeGuardState()
        guard.decide("sess-a", enabled = true, cap = 1, cooldownMs = 0L, now = 0L)
        val capA = guard.decide("sess-a", enabled = true, cap = 1, cooldownMs = 0L, now = 1L)
        val okB = guard.decide("sess-b", enabled = true, cap = 1, cooldownMs = 0L, now = 2L)

        assertEquals(AutoWakeGuardState.Decision.CAP_REACHED, capA,
            "session A has hit its cap")
        assertEquals(AutoWakeGuardState.Decision.PROCEED, okB,
            "session B's counter is independent from A's")
    }

    @Test
    fun `resetSession clears state for a single session`() {
        val guard = AutoWakeGuardState()
        guard.decide("s1", enabled = true, cap = 2, cooldownMs = 0L, now = 0L)
        guard.decide("s1", enabled = true, cap = 2, cooldownMs = 0L, now = 1L)
        guard.resetSession("s1")
        val afterReset = guard.decide("s1", enabled = true, cap = 2, cooldownMs = 0L, now = 2L)
        assertEquals(AutoWakeGuardState.Decision.PROCEED, afterReset,
            "after resetSession, the cap starts over from zero")
        assertEquals(1, guard.attemptCount("s1"))
    }

    @Test
    fun `reset clears all sessions`() {
        val guard = AutoWakeGuardState()
        guard.decide("x", enabled = true, cap = 1, cooldownMs = 0L, now = 0L)
        guard.decide("y", enabled = true, cap = 1, cooldownMs = 0L, now = 1L)
        guard.reset()
        assertEquals(0, guard.attemptCount("x"))
        assertEquals(0, guard.attemptCount("y"))
    }
}

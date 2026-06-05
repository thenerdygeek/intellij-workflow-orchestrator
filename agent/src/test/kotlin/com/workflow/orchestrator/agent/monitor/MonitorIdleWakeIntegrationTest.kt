package com.workflow.orchestrator.agent.monitor

import com.workflow.orchestrator.agent.tools.background.AutoWakeGuardState
import com.workflow.orchestrator.agent.tools.background.AutoWakeSettings
import com.workflow.orchestrator.agent.tools.background.IdleSessionWaker
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * End-to-end integration test: a monitor event drives the idle-wake guard correctly
 * when routing through the REAL [MonitorManager] → [IdleSessionWaker] → [AutoWakeGuardState]
 * chain.  No MockK on any of these three classes — all real instances.
 *
 * Wire: MonitorManager.wakeIdle = { text -> wakeOutcomeFor(waker.wake(sessionId, text, "monitor")) }
 */
class MonitorIdleWakeIntegrationTest {

    // ── Fixture ──────────────────────────────────────────────────────────────────

    /**
     * Holds all three real objects plus a mutable clock and a wake-listener accumulator.
     * [clockSetter] lets [advanceClock] propagate into the MonitorManager's clock lambda.
     */
    private class Fixture(
        val guards: AutoWakeGuardState,
        val waker: IdleSessionWaker,
        val mgr: MonitorManager,
        val wokeTexts: MutableList<String>,
        private var clockVal: Long = 0L,
        private val clockSetter: (Long) -> Unit,
    ) {
        fun advanceClock(ms: Long = 1L) {
            clockVal += ms
            clockSetter(clockVal)
        }

        /** Buffer one event then advance clock past coalesceWindowMs=0 and flush. */
        fun sendEventAndFlush(monitorId: String, severity: Severity, line: String) {
            mgr.onEvent(MonitorEvent(monitorId, severity, line))
            advanceClock(1L)
            mgr.flushDue()
        }
    }

    /**
     * Build a fully-wired fixture with real [MonitorManager], [IdleSessionWaker], and
     * [AutoWakeGuardState].
     *
     * @param sessionId        the session id used as the wake target
     * @param wakeBudget       [MonitorConfig.wakeBudgetPerMonitor]
     * @param guardEnabled     [AutoWakeSettings.enabled]
     * @param guardCap         [AutoWakeSettings.cap] (distinct from monitor budget)
     * @param cooldownMs       [AutoWakeSettings.cooldownMs]
     * @param activeSessionId  lambda that returns the currently active session id (BUG-#4 gate)
     */
    private fun fixture(
        sessionId: String,
        wakeBudget: Int = 2,
        guardEnabled: Boolean = true,
        guardCap: Int = 10,
        cooldownMs: Long = 0L,
        activeSessionId: () -> String? = { null },
    ): Fixture {
        val wokeTexts = mutableListOf<String>()
        var clockVal = 0L
        val guards = AutoWakeGuardState()

        val waker = IdleSessionWaker(
            guards = guards,
            settings = { AutoWakeSettings(guardEnabled, guardCap, cooldownMs) },
            listener = { { _, text -> wokeTexts.add(text) } },
            activeSessionId = activeSessionId,
            invoker = { it() },   // inline — no EDT in tests
            onLog = {},
        )

        val mgr = MonitorManager(
            config = MonitorConfig(
                coalesceWindowMs = 0L,          // flush immediately on any clock advance
                wakeBudgetPerMonitor = wakeBudget,
                floodThresholdPerMin = 20,
            ),
            clock = { clockVal },
            isLoopLive = { false },             // idle throughout
            deliverToLoop = {},
            wakeIdle = { text -> wakeOutcomeFor(waker.wake(sessionId, text, "monitor")) },
        )

        return Fixture(
            guards = guards,
            waker = waker,
            mgr = mgr,
            wokeTexts = wokeTexts,
            clockSetter = { v -> clockVal = v },
        )
    }

    // ── Case 1: NOTABLE event wakes idle loop ────────────────────────────────────

    @Test
    fun `case1 NOTABLE event wakes idle loop - listener invoked and guard count is 1 and budget not yet exhausted`() {
        val f = fixture(sessionId = "sess-1", wakeBudget = 2)
        val monId = "mon-c1"

        f.sendEventAndFlush(monId, Severity.NOTABLE, "build finished")

        assertEquals(1, f.wokeTexts.size, "listener must be called exactly once")
        assertTrue(f.wokeTexts[0].contains("build finished"),
            "listener text must contain the event line; got: ${f.wokeTexts[0]}")
        assertEquals(1, f.guards.attemptCount("sess-1"),
            "guard attempt count must be 1 after one WAKE")
        assertFalse(f.mgr.isDormant(monId),
            "monitor must not be dormant after spending 1 of 2 budget units")
    }

    // ── Case 2: ALERT also wakes ──────────────────────────────────────────────────

    @Test
    fun `case2 ALERT event wakes idle loop same as NOTABLE`() {
        val f = fixture(sessionId = "sess-2", wakeBudget = 3)
        val monId = "mon-c2"

        f.sendEventAndFlush(monId, Severity.ALERT, "OOM detected")

        assertEquals(1, f.wokeTexts.size, "ALERT must wake the idle loop")
        assertTrue(f.wokeTexts[0].contains("OOM detected"))
        assertEquals(1, f.guards.attemptCount("sess-2"))
        assertFalse(f.mgr.isDormant(monId),
            "monitor with budget=3 must not be dormant after 1 wake")
    }

    // ── Case 3: INFO never wakes ──────────────────────────────────────────────────

    @Test
    fun `case3 INFO event never wakes idle loop and guard count stays zero`() {
        val f = fixture(sessionId = "sess-3", wakeBudget = 2)
        val monId = "mon-c3"

        f.sendEventAndFlush(monId, Severity.INFO, "heartbeat tick")

        assertTrue(f.wokeTexts.isEmpty(),
            "INFO is not wakeEligible — listener must not be called")
        assertEquals(0, f.guards.attemptCount("sess-3"),
            "guard count must remain 0 — INFO does not reach the wake path")
    }

    // ── Case 4: Budget exhaustion → dormant ───────────────────────────────────────

    @Test
    fun `case4 budget exhaustion makes monitor dormant and subsequent events do not wake`() {
        val f = fixture(sessionId = "sess-4", wakeBudget = 2, guardCap = 10)
        val monId = "mon-c4"

        // 1st NOTABLE → budget 2→1, not dormant
        f.sendEventAndFlush(monId, Severity.NOTABLE, "event-1")
        assertFalse(f.mgr.isDormant(monId), "after 1st wake monitor must not be dormant")
        assertEquals(1, f.wokeTexts.size)

        // 2nd NOTABLE → budget 1→0, goes dormant
        f.sendEventAndFlush(monId, Severity.NOTABLE, "event-2")
        assertTrue(f.mgr.isDormant(monId), "after 2nd wake budget is 0 — monitor must be dormant")
        assertEquals(2, f.wokeTexts.size)

        // 3rd NOTABLE → monitor is dormant — listener must NOT fire
        f.sendEventAndFlush(monId, Severity.NOTABLE, "event-3")
        assertEquals(2, f.wokeTexts.size,
            "dormant monitor must not invoke the listener on further events")
    }

    // ── Case 5: Guard-blocked (SKIP) does NOT spend budget ───────────────────────

    @Test
    fun `case5 guard-blocked SKIP does not spend budget and a later permissive waker can still wake`() {
        // guardEnabled=false → AutoWakeGuardState.Decision.DISABLED → IdleWakeRoute.SKIP_GUARD
        // → WakeOutcome.SKIPPED → MonitorManager does NOT decrement budget
        val f = fixture(sessionId = "sess-5", wakeBudget = 2, guardEnabled = false)
        val monId = "mon-c5"

        repeat(5) { i ->
            f.sendEventAndFlush(monId, Severity.NOTABLE, "skip-event-$i")
        }

        // Listener must never have been called
        assertTrue(f.wokeTexts.isEmpty(),
            "guard-blocked path must not invoke the listener")

        // Monitor must NOT be dormant (budget never decremented by SKIP)
        assertFalse(f.mgr.isDormant(monId),
            "monitor must not be dormant when all wakes were SKIPPED — budget was not spent")

        // Prove the budget-not-spent invariant: build a PERMISSIVE waker for a fresh session
        // and drive the same monitorId — it MUST wake successfully.
        val wokeTexts2 = mutableListOf<String>()
        var clockVal2 = 0L
        val f2 = run {
            val guards2 = AutoWakeGuardState()
            val waker2 = IdleSessionWaker(
                guards = guards2,
                settings = { AutoWakeSettings(true, 10, 0L) },
                listener = { { _, text -> wokeTexts2.add(text) } },
                invoker = { it() },
                onLog = {},
            )
            val mgr2 = MonitorManager(
                config = MonitorConfig(coalesceWindowMs = 0L, wakeBudgetPerMonitor = 2, floodThresholdPerMin = 20),
                clock = { clockVal2 },
                isLoopLive = { false },
                deliverToLoop = {},
                wakeIdle = { text -> wakeOutcomeFor(waker2.wake("sess-5-permissive", text, "monitor")) },
            )
            Triple(guards2, mgr2, wokeTexts2)
        }
        val (_, mgr2, _) = f2
        mgr2.onEvent(MonitorEvent(monId, Severity.NOTABLE, "permissive-wake"))
        clockVal2 += 1L
        mgr2.flushDue()

        assertEquals(1, wokeTexts2.size,
            "permissive waker must succeed — proving SKIP left its monitor budget intact")
        // The original fixture's monitor is still non-dormant after all the SKIPs
        assertFalse(f.mgr.isDormant(monId),
            "original monitor must still be non-dormant after repeated SKIPs")
    }

    // ── Case 6: DEFER_ACTIVE_SESSION does not spend budget ───────────────────────

    @Test
    fun `case6 DEFER_ACTIVE_SESSION does not spend budget and monitor stays non-dormant`() {
        val targetSessionId = "sess-6-target"
        val differentActiveSessionId = "sess-6-other"

        // The activeSessionId lambda returns a DIFFERENT session id → wake() short-circuits
        // with DEFER_ACTIVE_SESSION (before even consulting the guard).
        val f = fixture(
            sessionId = targetSessionId,
            wakeBudget = 2,
            guardEnabled = true,
            guardCap = 10,
            activeSessionId = { differentActiveSessionId },
        )
        val monId = "mon-c6"

        repeat(4) { i ->
            f.sendEventAndFlush(monId, Severity.NOTABLE, "deferred-event-$i")
        }

        // Listener must NOT have been called
        assertTrue(f.wokeTexts.isEmpty(),
            "DEFER_ACTIVE_SESSION must not invoke the listener")

        // Guard is NOT consulted before DEFER_ACTIVE_SESSION → attempt count stays 0
        assertEquals(0, f.guards.attemptCount(targetSessionId),
            "guard attempt count must be 0 — DEFER_ACTIVE_SESSION is returned before the guard is consulted")

        // wakeOutcomeFor(DEFER_ACTIVE_SESSION) == WakeOutcome.DEFERRED →
        // MonitorManager treats it like SKIPPED (no budget decrement)
        assertFalse(f.mgr.isDormant(monId),
            "monitor must not be dormant when all wakes were DEFERRED_ACTIVE_SESSION")
    }
}

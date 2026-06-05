package com.workflow.orchestrator.agent.monitor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MonitorManagerTest {

    private class Harness {
        var now = 0L
        val delivered = mutableListOf<String>()   // text routed to the LIVE loop
        val woke = mutableListOf<String>()         // text routed via idle-wake
        var loopLive = false
        var wakeRoute = WakeOutcome.WOKE           // what the injected waker returns
        val floodStopped = mutableListOf<String>() // monitor ids the flood-stop hook fired for

        val mgr = MonitorManager(
            config = MonitorConfig(coalesceWindowMs = 100, wakeBudgetPerMonitor = 2, floodThresholdPerMin = 5),
            clock = { now },
            isLoopLive = { loopLive },
            deliverToLoop = { text -> delivered += text },
            wakeIdle = { text -> woke += text; wakeRoute },
            onFloodStop = { floodStopped += it },
        )
    }

    @Test
    fun `events within the coalesce window collapse into one delivery`() {
        val h = Harness(); h.loopLive = true
        h.mgr.onEvent(MonitorEvent("m1", Severity.NOTABLE, "a"))
        h.now = 50
        h.mgr.onEvent(MonitorEvent("m1", Severity.NOTABLE, "b"))
        h.now = 200                       // past the 100ms window
        h.mgr.flushDue()
        assertEquals(1, h.delivered.size)
        assertEquals(true, h.delivered[0].contains("a") && h.delivered[0].contains("b"))
    }

    @Test
    fun `idle NOTABLE events wake until the per-monitor budget is exhausted then go dormant`() {
        val h = Harness(); h.loopLive = false
        repeat(3) { i ->
            h.mgr.onEvent(MonitorEvent("m1", Severity.ALERT, "e$i"))
            h.now += 200; h.mgr.flushDue()
        }
        assertEquals(2, h.woke.size)                 // budget = 2
        assertEquals(true, h.mgr.isDormant("m1"))
    }

    @Test
    fun `SKIP_GUARD route does not consume wake budget`() {
        val h = Harness(); h.loopLive = false; h.wakeRoute = WakeOutcome.SKIPPED
        repeat(4) { i ->
            h.mgr.onEvent(MonitorEvent("m1", Severity.ALERT, "e$i"))
            h.now += 200; h.mgr.flushDue()
        }
        assertEquals(false, h.mgr.isDormant("m1"))   // never decremented
    }

    @Test
    fun `INFO events do not wake an idle loop`() {
        val h = Harness(); h.loopLive = false
        h.mgr.onEvent(MonitorEvent("m1", Severity.INFO, "tick"))
        h.now = 200; h.mgr.flushDue()
        assertEquals(0, h.woke.size)
    }

    @Test
    fun `flood beyond threshold per minute auto-stops the monitor`() {
        val h = Harness(); h.loopLive = true
        repeat(6) { i ->
            h.mgr.onEvent(MonitorEvent("m1", Severity.INFO, "e$i"))
            h.now += 200; h.mgr.flushDue()
        }
        assertEquals(true, h.mgr.isAutoStopped("m1"))
    }

    @Test
    fun `flood auto-stop fires onFloodStop hook to actually stop the source`() {
        val h = Harness(); h.loopLive = true
        repeat(6) { i ->
            h.mgr.onEvent(MonitorEvent("m1", Severity.INFO, "e$i"))
            h.now += 200; h.mgr.flushDue()
        }
        assertEquals(true, h.mgr.isAutoStopped("m1"))
        assertEquals(listOf("m1"), h.floodStopped)
    }

    @Test
    fun `coalesced batch with mixed INFO and NOTABLE events wakes idle loop`() {
        val h = Harness(); h.loopLive = false
        h.mgr.onEvent(MonitorEvent("m1", Severity.INFO, "tick"))
        h.now = 50
        h.mgr.onEvent(MonitorEvent("m1", Severity.NOTABLE, "status changed"))
        h.now = 200; h.mgr.flushDue()
        assertEquals(1, h.woke.size)   // NOTABLE in the batch triggers the wake despite the INFO sibling
    }

    @Test
    fun `budget of 1 allows exactly one wake then dormant`() {
        val h = Harness()
        val mgr = MonitorManager(
            config = MonitorConfig(coalesceWindowMs = 100, wakeBudgetPerMonitor = 1, floodThresholdPerMin = 5),
            clock = { h.now },
            isLoopLive = { h.loopLive },
            deliverToLoop = { text -> h.delivered += text },
            wakeIdle = { text -> h.woke += text; h.wakeRoute },
        )
        h.loopLive = false
        mgr.onEvent(MonitorEvent("m1", Severity.ALERT, "e0"))
        h.now = 200; mgr.flushDue()
        assertEquals(1, h.woke.size)
        assertEquals(true, mgr.isDormant("m1"))
    }

    // ── markAllDormant tests ─────────────────────────────────────────────────

    @Test
    fun `markAllDormant after NOTABLE event prevents idle-wake on subsequent flushDue`() {
        val h = Harness(); h.loopLive = false
        // Buffer a NOTABLE event so the id lands in `pending`
        h.mgr.onEvent(MonitorEvent("m1", Severity.NOTABLE, "alert line"))
        // Mark dormant BEFORE the coalesce window expires
        h.mgr.markAllDormant()
        // Now advance past the coalesce window and flush
        h.now = 200; h.mgr.flushDue()
        // Dormant — wakeIdle must NOT have been called
        assertEquals(0, h.woke.size)
        assertEquals(true, h.mgr.isDormant("m1"))
    }

    @Test
    fun `markAllDormant with no prior events is a no-op and does not crash`() {
        val h = Harness()
        // Should not throw
        h.mgr.markAllDormant()
        // Nothing to wake, nothing dormant by id
        assertEquals(0, h.woke.size)
    }

    @Test
    fun `monitor dormant via markAllDormant drops events from wake path same as budget-exhausted dormancy`() {
        val h = Harness(); h.loopLive = false
        // Seed two NOTABLE events so the id enters pending AND wakeBudget maps
        h.mgr.onEvent(MonitorEvent("m2", Severity.NOTABLE, "first"))
        h.now = 200; h.mgr.flushDue()           // first flush — wakes once (budget decremented to 1)
        assertEquals(1, h.woke.size)
        // Now mark all dormant (simulating abnormal loop exit)
        h.mgr.markAllDormant()
        assertEquals(true, h.mgr.isDormant("m2"))
        // A subsequent NOTABLE event and flush should NOT invoke wakeIdle
        h.mgr.onEvent(MonitorEvent("m2", Severity.NOTABLE, "second"))
        h.now = 400; h.mgr.flushDue()
        assertEquals(1, h.woke.size)             // still 1 — no additional wakes
    }
}

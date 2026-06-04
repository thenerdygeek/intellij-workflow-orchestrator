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

        val mgr = MonitorManager(
            config = MonitorConfig(coalesceWindowMs = 100, wakeBudgetPerMonitor = 2, floodThresholdPerMin = 5),
            clock = { now },
            isLoopLive = { loopLive },
            deliverToLoop = { text -> delivered += text },
            wakeIdle = { text -> woke += text; wakeRoute },
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
}

package com.workflow.orchestrator.agent.monitor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MonitorEventTest {
    @Test
    fun `wake eligibility follows severity`() {
        assertTrue(MonitorEvent("m1", Severity.ALERT, "build failed").wakeEligible)
        assertTrue(MonitorEvent("m1", Severity.NOTABLE, "stage running").wakeEligible)
        assertEquals(false, MonitorEvent("m1", Severity.INFO, "tick").wakeEligible)
    }

    @Test
    fun `formatLine prefixes the monitor id and severity`() {
        val e = MonitorEvent("bamboo-PLAN", Severity.ALERT, "PLAN-42 FAILED")
        assertEquals("[monitor bamboo-PLAN · ALERT] PLAN-42 FAILED", e.formatLine())
    }
}

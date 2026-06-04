package com.workflow.orchestrator.agent.monitor

import com.workflow.orchestrator.agent.tools.background.IdleWakeRoute
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MonitorWakeRoutingTest {
    @Test
    fun `IdleWakeRoute maps to WakeOutcome`() {
        assertEquals(WakeOutcome.WOKE, wakeOutcomeFor(IdleWakeRoute.WAKE))
        assertEquals(WakeOutcome.SKIPPED, wakeOutcomeFor(IdleWakeRoute.SKIP_GUARD))
        assertEquals(WakeOutcome.DEFERRED, wakeOutcomeFor(IdleWakeRoute.DEFER_ACTIVE_SESSION))
        assertEquals(WakeOutcome.DEFERRED, wakeOutcomeFor(IdleWakeRoute.DEFER_NO_LISTENER))
    }
}

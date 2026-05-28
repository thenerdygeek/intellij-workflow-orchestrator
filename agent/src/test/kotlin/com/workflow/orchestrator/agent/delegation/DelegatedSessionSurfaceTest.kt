package com.workflow.orchestrator.agent.delegation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DelegatedSessionSurfaceTest {
    @Test fun `idle tab runs the delegation now`() {
        assertEquals(DelegatedSurface.RUN_NOW, DelegatedSessionSurface.decide(tabBusy = false))
    }
    @Test fun `busy tab queues the delegation as incoming`() {
        assertEquals(DelegatedSurface.QUEUE_INCOMING, DelegatedSessionSurface.decide(tabBusy = true))
    }
}

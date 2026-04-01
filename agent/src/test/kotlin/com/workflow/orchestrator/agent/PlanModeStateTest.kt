package com.workflow.orchestrator.agent

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.AfterEach

class PlanModeStateTest {
    @AfterEach
    fun cleanup() {
        AgentService.planModeActive.set(false)
    }

    @Test
    fun `planModeActive defaults to false`() {
        assertFalse(AgentService.planModeActive.get())
    }

    @Test
    fun `planModeActive can be toggled`() {
        AgentService.planModeActive.set(true)
        assertTrue(AgentService.planModeActive.get())
        AgentService.planModeActive.set(false)
        assertFalse(AgentService.planModeActive.get())
    }
}

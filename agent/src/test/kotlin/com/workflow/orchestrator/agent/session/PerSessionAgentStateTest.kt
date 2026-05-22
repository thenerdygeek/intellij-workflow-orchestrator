package com.workflow.orchestrator.agent.session

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PerSessionAgentStateTest {
    @Test
    fun `default plan mode is false`() {
        val state = PerSessionAgentState(sessionId = "s1")
        assertFalse(state.planModeActive.get())
    }

    @Test
    fun `set true is observable on get`() {
        val state = PerSessionAgentState(sessionId = "s1")
        state.planModeActive.set(true)
        assertTrue(state.planModeActive.get())
    }

    @Test
    fun `two instances have independent plan mode flags`() {
        val a = PerSessionAgentState(sessionId = "a")
        val b = PerSessionAgentState(sessionId = "b")
        a.planModeActive.set(true)
        assertFalse(b.planModeActive.get())
        assertTrue(a.planModeActive.get())
    }
}

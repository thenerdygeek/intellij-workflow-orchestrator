package com.workflow.orchestrator.agent

import com.workflow.orchestrator.agent.session.PerSessionAgentState
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pins the invariant that concurrent sessions in one project do not share
 * plan-mode state. This invariant is required by the cross-IDE delegation
 * spec (§6.1) — a delegated session must not inherit or clobber IDE-B's
 * existing plan-mode toggle.
 */
class AgentServicePlanModeIsolationTest {

    @Test
    fun `setting plan mode on one session does not affect another`() {
        val stateA = PerSessionAgentState(sessionId = "alpha")
        val stateB = PerSessionAgentState(sessionId = "beta")

        stateA.planModeActive.set(true)

        assertTrue(stateA.planModeActive.get())
        assertFalse(stateB.planModeActive.get())
    }

    @Test
    fun `independent instances retain independent values after both are set`() {
        val a = PerSessionAgentState(sessionId = "a")
        val b = PerSessionAgentState(sessionId = "b")
        a.planModeActive.set(true)
        b.planModeActive.set(false)
        assertTrue(a.planModeActive.get())
        assertFalse(b.planModeActive.get())
    }
}

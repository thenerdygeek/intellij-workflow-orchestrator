package com.workflow.orchestrator.agent.runtime

import com.workflow.orchestrator.agent.AgentService
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.AfterEach

class PlanModeExecutionGuardTest {

    @AfterEach
    fun cleanup() {
        AgentService.planModeActive.set(false)
    }

    @Test
    fun `isPlanModeBlocked returns true for edit_file when plan mode active`() {
        AgentService.planModeActive.set(true)
        assertTrue(SingleAgentSession.isPlanModeBlocked("edit_file"))
    }

    @Test
    fun `isPlanModeBlocked returns true for create_file when plan mode active`() {
        AgentService.planModeActive.set(true)
        assertTrue(SingleAgentSession.isPlanModeBlocked("create_file"))
    }

    @Test
    fun `isPlanModeBlocked returns false for edit_file when plan mode inactive`() {
        AgentService.planModeActive.set(false)
        assertFalse(SingleAgentSession.isPlanModeBlocked("edit_file"))
    }

    @Test
    fun `isPlanModeBlocked returns false for read_file regardless of plan mode`() {
        AgentService.planModeActive.set(true)
        assertFalse(SingleAgentSession.isPlanModeBlocked("read_file"))
    }

    @Test
    fun `isPlanModeBlocked returns false for run_command in plan mode`() {
        AgentService.planModeActive.set(true)
        assertFalse(SingleAgentSession.isPlanModeBlocked("run_command"))
    }

    @Test
    fun `isPlanModeBlocked returns false for runtime and debug in plan mode`() {
        AgentService.planModeActive.set(true)
        assertFalse(SingleAgentSession.isPlanModeBlocked("runtime"))
        assertFalse(SingleAgentSession.isPlanModeBlocked("debug"))
    }
}

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

    // ── Meta-tool action filtering tests ──

    @Test
    fun `PLAN_MODE_BLOCKED_ACTIONS contains jira write actions`() {
        val blocked = SingleAgentSession.PLAN_MODE_BLOCKED_ACTIONS
        assertTrue("transition" in blocked)
        assertTrue("comment" in blocked)
        assertTrue("log_work" in blocked)
        assertTrue("start_work" in blocked)
    }

    @Test
    fun `PLAN_MODE_BLOCKED_ACTIONS contains bamboo write actions`() {
        val blocked = SingleAgentSession.PLAN_MODE_BLOCKED_ACTIONS
        assertTrue("trigger_build" in blocked)
        assertTrue("stop_build" in blocked)
        assertTrue("cancel_build" in blocked)
    }

    @Test
    fun `PLAN_MODE_BLOCKED_ACTIONS contains bitbucket write actions`() {
        val blocked = SingleAgentSession.PLAN_MODE_BLOCKED_ACTIONS
        assertTrue("create_pr" in blocked)
        assertTrue("merge_pr" in blocked)
        assertTrue("approve_pr" in blocked)
    }

    @Test
    fun `PLAN_MODE_BLOCKED_ACTIONS does not contain read actions`() {
        val blocked = SingleAgentSession.PLAN_MODE_BLOCKED_ACTIONS
        assertFalse("get_ticket" in blocked)
        assertFalse("search_issues" in blocked)
        assertFalse("get_sprints" in blocked)
        assertFalse("build_status" in blocked)
        assertFalse("get_build_log" in blocked)
    }
}

package com.workflow.orchestrator.agent.runtime

import com.workflow.orchestrator.agent.AgentService
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.AfterEach

class PlanModeTransitionTest {

    @AfterEach
    fun cleanup() {
        AgentService.planModeActive.set(false)
    }

    @Test
    fun `approvePlan clears planModeActive`() {
        AgentService.planModeActive.set(true)
        val pm = PlanManager()
        val plan = AgentPlan(goal = "test", steps = listOf(PlanStep(id = "1", title = "step 1")))
        pm.restorePlan(plan)
        pm.approvePlan()
        assertFalse(AgentService.planModeActive.get(), "planModeActive should be false after approval")
    }

    @Test
    fun `auto-approval via timeout clears planModeActive`() {
        AgentService.planModeActive.set(true)
        val pm = PlanManager()
        val plan = AgentPlan(goal = "test", steps = listOf(PlanStep(id = "1", title = "step 1")))
        kotlinx.coroutines.runBlocking {
            pm.submitPlanAndWait(plan, timeoutMs = 50)
        }
        assertFalse(AgentService.planModeActive.get(), "planModeActive should be false after auto-approval")
    }
}

package com.workflow.orchestrator.agent.tools.builtin

import com.workflow.orchestrator.agent.runtime.*
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CreatePlanToolTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `PlanStep serialization round-trip`() {
        val step = PlanStep(id = "1", title = "Read file", description = "Read the auth module", files = listOf("Auth.kt"), action = "read")
        val serialized = json.encodeToString(PlanStep.serializer(), step)
        val deserialized = json.decodeFromString(PlanStep.serializer(), serialized)
        assertEquals(step, deserialized)
    }

    @Test
    fun `AgentPlan serialization round-trip`() {
        val plan = AgentPlan(
            goal = "Fix auth bug",
            approach = "Replace session with JWT",
            steps = listOf(
                PlanStep("1", "Analyze", "Read code", listOf("Auth.kt"), "read"),
                PlanStep("2", "Fix", "Edit code", listOf("Auth.kt"), "edit")
            ),
            testing = "Run auth tests"
        )
        val serialized = json.encodeToString(AgentPlan.serializer(), plan)
        val deserialized = json.decodeFromString(AgentPlan.serializer(), serialized)
        assertEquals(plan.goal, deserialized.goal)
        assertEquals(2, deserialized.steps.size)
    }

    @Test
    fun `PlanManager approve completes future`() {
        val manager = PlanManager()
        val plan = AgentPlan("test", steps = listOf(PlanStep("1", "step")))
        val future = manager.submitPlan(plan)

        assertFalse(future.isDone)
        manager.approvePlan()
        assertTrue(future.isDone)
        assertTrue(future.get() is PlanApprovalResult.Approved)
        assertTrue(manager.isPlanApproved())
    }

    @Test
    fun `PlanManager revise completes future with comments`() {
        val manager = PlanManager()
        val plan = AgentPlan("test", steps = listOf(PlanStep("1", "step")))
        val future = manager.submitPlan(plan)

        manager.revisePlan(mapOf("1" to "use different approach"))
        assertTrue(future.isDone)
        val result = future.get() as PlanApprovalResult.Revised
        assertEquals("use different approach", result.comments["1"])
    }

    @Test
    fun `PlanManager updateStepStatus changes step`() {
        val manager = PlanManager()
        val plan = AgentPlan("test", steps = listOf(PlanStep("1", "step"), PlanStep("2", "step2")))
        manager.submitPlan(plan)

        manager.updateStepStatus("1", "running")
        assertEquals("running", manager.currentPlan?.steps?.find { it.id == "1" }?.status)

        manager.updateStepStatus("1", "done")
        assertEquals("done", manager.currentPlan?.steps?.find { it.id == "1" }?.status)
    }

    @Test
    fun `tool metadata is correct`() {
        val tool = CreatePlanTool()
        assertEquals("create_plan", tool.name)
        assertTrue(tool.parameters.required.containsAll(listOf("goal", "steps")))

        val updateTool = UpdatePlanStepTool()
        assertEquals("update_plan_step", updateTool.name)
        assertTrue(updateTool.parameters.required.containsAll(listOf("step_id", "status")))
    }
}

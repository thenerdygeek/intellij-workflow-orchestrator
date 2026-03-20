package com.workflow.orchestrator.agent.context

import com.workflow.orchestrator.agent.runtime.AgentPlan
import com.workflow.orchestrator.agent.runtime.PlanStep
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PlanAnchorTest {

    @Test
    fun `buildSummary creates structured plan with status icons`() {
        val plan = AgentPlan(goal = "Refactor auth", steps = listOf(
            PlanStep(id = "1", title = "Analyze", status = "done"),
            PlanStep(id = "2", title = "Implement", status = "running"),
            PlanStep(id = "3", title = "Test", status = "pending")
        ))
        val summary = PlanAnchor.buildSummary(plan)
        assertTrue(summary.contains("<active_plan>"))
        assertTrue(summary.contains("Refactor auth"))
        assertTrue(summary.contains("1.✓ Analyze"))
        assertTrue(summary.contains("2.◉ Implement"))
        assertTrue(summary.contains("3.○ Test"))
        assertTrue(summary.contains("</active_plan>"))
    }

    @Test
    fun `buildSummary shows completion count`() {
        val plan = AgentPlan(goal = "Test", steps = listOf(
            PlanStep(id = "1", title = "A", status = "done"),
            PlanStep(id = "2", title = "B", status = "done"),
            PlanStep(id = "3", title = "C", status = "pending")
        ))
        val summary = PlanAnchor.buildSummary(plan)
        assertTrue(summary.contains("2/3"))
    }

    @Test
    fun `buildSummary includes files modified from done steps only`() {
        val plan = AgentPlan(goal = "Test", steps = listOf(
            PlanStep(id = "1", title = "Edit", status = "done", files = listOf("Foo.kt", "Bar.kt")),
            PlanStep(id = "2", title = "Pending", status = "pending", files = listOf("Baz.kt"))
        ))
        val summary = PlanAnchor.buildSummary(plan)
        assertTrue(summary.contains("Foo.kt"))
        assertTrue(summary.contains("Bar.kt"))
        assertFalse(summary.contains("Baz.kt"))
    }

    @Test
    fun `findPlanMessageIndex returns correct index`() {
        val contents = listOf("System prompt", "<active_plan>\nOld\n</active_plan>", "User msg")
        assertEquals(1, PlanAnchor.findPlanMessageIndex(contents))
    }

    @Test
    fun `findPlanMessageIndex returns -1 when not found`() {
        assertEquals(-1, PlanAnchor.findPlanMessageIndex(listOf("No plan here")))
    }

    @Test
    fun `isPlanMessage detects plan content`() {
        assertTrue(PlanAnchor.isPlanMessage("<active_plan>\nGoal: X\n</active_plan>"))
        assertFalse(PlanAnchor.isPlanMessage("Regular message"))
    }

    @Test
    fun `createPlanMessage returns system role message`() {
        val plan = AgentPlan(goal = "Test", steps = listOf(PlanStep(id = "1", title = "A")))
        val msg = PlanAnchor.createPlanMessage(plan)
        assertEquals("system", msg.role)
        assertTrue(msg.content!!.contains("<active_plan>"))
    }
}

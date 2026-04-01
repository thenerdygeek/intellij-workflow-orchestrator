package com.workflow.orchestrator.agent.tools.builtin

import com.workflow.orchestrator.agent.runtime.*
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CreatePlanToolTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `PlanStep serialization round-trip`() {
        val step = PlanStep(id = "step-1", title = "Read file", description = "Read the auth module", files = listOf("Auth.kt"))
        val serialized = json.encodeToString(PlanStep.serializer(), step)
        val deserialized = json.decodeFromString(PlanStep.serializer(), serialized)
        assertEquals(step, deserialized)
    }

    @Test
    fun `AgentPlan serialization round-trip`() {
        val plan = AgentPlan(
            goal = "Fix auth bug",
            steps = listOf(
                PlanStep("step-1", "Analyze", "Read code", listOf("Auth.kt")),
                PlanStep("step-2", "Fix", "Edit code", listOf("Auth.kt"))
            ),
            title = "Auth Fix",
            markdown = "## Goal\nFix auth bug\n\n## Steps\n### 1. Analyze\n### 2. Fix"
        )
        val serialized = json.encodeToString(AgentPlan.serializer(), plan)
        val deserialized = json.decodeFromString(AgentPlan.serializer(), serialized)
        assertEquals(plan.goal, deserialized.goal)
        assertEquals(2, deserialized.steps.size)
    }

    @Test
    fun `PlanManager approve completes future`() {
        val manager = PlanManager()
        val plan = AgentPlan("test", steps = listOf(PlanStep("step-1", "step")))
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
        val plan = AgentPlan("test", steps = listOf(PlanStep("step-1", "step")))
        val future = manager.submitPlan(plan)

        manager.revisePlan(mapOf("step-1" to "use different approach"))
        assertTrue(future.isDone)
        val result = future.get() as PlanApprovalResult.Revised
        assertEquals("use different approach", result.comments["step-1"])
    }

    @Test
    fun `PlanManager updateStepStatus changes step`() {
        val manager = PlanManager()
        val plan = AgentPlan("test", steps = listOf(PlanStep("step-1", "step"), PlanStep("step-2", "step2")))
        manager.submitPlan(plan)

        manager.updateStepStatus("step-1", "running")
        assertEquals("running", manager.currentPlan?.steps?.find { it.id == "step-1" }?.status)

        manager.updateStepStatus("step-1", "done")
        assertEquals("done", manager.currentPlan?.steps?.find { it.id == "step-1" }?.status)
    }

    @Test
    fun `tool metadata is correct`() {
        val tool = CreatePlanTool()
        assertEquals("create_plan", tool.name)
        assertTrue(tool.parameters.required.containsAll(listOf("title", "markdown")))

        val updateTool = UpdatePlanStepTool()
        assertEquals("update_plan_step", updateTool.name)
        assertTrue(updateTool.parameters.required.containsAll(listOf("step_id", "status")))
    }

    // ── Markdown step extraction tests ──

    @Test
    fun `extractStepsFromMarkdown parses numbered headings`() {
        val markdown = """
            ## Goal
            Fix the auth module

            ## Steps
            ### 1. Read the existing code
            Read `Auth.kt` and `UserService.kt` to understand the current flow.

            ### 2. Add JWT validation
            Edit `Auth.kt` to add JWT token validation.

            ### 3. Update tests
            Run existing tests and add new ones for JWT flow.

            ## Testing
            Run `./gradlew test`
        """.trimIndent()

        val steps = CreatePlanTool.extractStepsFromMarkdown(markdown)
        assertEquals(3, steps.size)

        assertEquals("step-1", steps[0].id)
        assertEquals("Read the existing code", steps[0].title)
        assertTrue(steps[0].files.contains("Auth.kt"))
        assertTrue(steps[0].files.contains("UserService.kt"))

        assertEquals("step-2", steps[1].id)
        assertEquals("Add JWT validation", steps[1].title)
        assertTrue(steps[1].files.contains("Auth.kt"))

        assertEquals("step-3", steps[2].id)
        assertEquals("Update tests", steps[2].title)
    }

    @Test
    fun `extractStepsFromMarkdown handles Step N colon format`() {
        val markdown = """
            ## Steps
            ### Step 1: Create the registry
            New file at `src/main/Registry.kt`.

            ### Step 2: Wire into service
            Modify `src/main/Service.kt`.
        """.trimIndent()

        val steps = CreatePlanTool.extractStepsFromMarkdown(markdown)
        assertEquals(2, steps.size)
        assertEquals("Create the registry", steps[0].title)
        assertEquals("Wire into service", steps[1].title)
    }

    @Test
    fun `extractStepsFromMarkdown handles unnumbered headings`() {
        val markdown = """
            ## Steps
            ### Create FileOwnershipRegistry
            New class for tracking file ownership.

            ### Wire into EditFileTool
            Add ownership check before edits.
        """.trimIndent()

        val steps = CreatePlanTool.extractStepsFromMarkdown(markdown)
        assertEquals(2, steps.size)
        assertEquals("Create FileOwnershipRegistry", steps[0].title)
        assertEquals("Wire into EditFileTool", steps[1].title)
    }

    @Test
    fun `extractStepsFromMarkdown returns empty for no headings`() {
        val markdown = """
            ## Goal
            Just a goal with no steps.

            Some explanation text.
        """.trimIndent()

        val steps = CreatePlanTool.extractStepsFromMarkdown(markdown)
        assertTrue(steps.isEmpty())
    }

    @Test
    fun `extractStepsFromMarkdown extracts file paths from backticks`() {
        val markdown = """
            ### 1. Update the service
            Edit `com/example/AuthService.kt` and `com/example/UserService.kt`.
            Also check `build.gradle.kts`.
        """.trimIndent()

        val steps = CreatePlanTool.extractStepsFromMarkdown(markdown)
        assertEquals(1, steps.size)
        assertEquals(3, steps[0].files.size)
        assertTrue(steps[0].files.contains("com/example/AuthService.kt"))
        assertTrue(steps[0].files.contains("com/example/UserService.kt"))
        assertTrue(steps[0].files.contains("build.gradle.kts"))
    }

    @Test
    fun `extractSection extracts Goal section`() {
        val markdown = """
            ## Goal
            Fix the authentication module to use JWT tokens.

            ## Approach
            Replace session-based auth with JWT.
        """.trimIndent()

        val goal = CreatePlanTool.extractSection(markdown, "Goal")
        assertEquals("Fix the authentication module to use JWT tokens.", goal)
    }

    @Test
    fun `extractSection returns null for missing section`() {
        val markdown = "## Steps\n### 1. Do something"
        assertNull(CreatePlanTool.extractSection(markdown, "Goal"))
    }
}

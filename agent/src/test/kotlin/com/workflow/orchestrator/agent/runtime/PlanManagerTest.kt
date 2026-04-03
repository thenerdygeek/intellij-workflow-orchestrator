package com.workflow.orchestrator.agent.runtime

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class PlanManagerTest {

    private lateinit var planManager: PlanManager

    @TempDir
    lateinit var tempDir: Path

    @BeforeEach
    fun setup() {
        planManager = PlanManager()
        planManager.sessionDir = tempDir.toFile()
    }

    // --- Step Dependencies ---

    @Test
    fun `PlanStep supports dependsOn field`() {
        val step = PlanStep(
            id = "step-2",
            title = "Implement feature",
            dependsOn = listOf("step-1")
        )
        assertEquals(listOf("step-1"), step.dependsOn)
    }

    @Test
    fun `areDependenciesMet returns true when no dependencies`() {
        val plan = AgentPlan(
            goal = "Test",
            steps = listOf(PlanStep(id = "s1", title = "First step"))
        )
        planManager.restorePlan(plan)
        assertTrue(planManager.areDependenciesMet("s1"))
    }

    @Test
    fun `areDependenciesMet returns false when dependency not done`() {
        val plan = AgentPlan(
            goal = "Test",
            steps = listOf(
                PlanStep(id = "s1", title = "First", status = "pending"),
                PlanStep(id = "s2", title = "Second", dependsOn = listOf("s1"))
            )
        )
        planManager.restorePlan(plan)
        assertFalse(planManager.areDependenciesMet("s2"))
    }

    @Test
    fun `areDependenciesMet returns true when dependency is done`() {
        val plan = AgentPlan(
            goal = "Test",
            steps = listOf(
                PlanStep(id = "s1", title = "First", status = "done"),
                PlanStep(id = "s2", title = "Second", dependsOn = listOf("s1"))
            )
        )
        planManager.restorePlan(plan)
        assertTrue(planManager.areDependenciesMet("s2"))
    }

    @Test
    fun `areDependenciesMet returns true when dependency is skipped`() {
        val plan = AgentPlan(
            goal = "Test",
            steps = listOf(
                PlanStep(id = "s1", title = "First", status = "skipped"),
                PlanStep(id = "s2", title = "Second", dependsOn = listOf("s1"))
            )
        )
        planManager.restorePlan(plan)
        assertTrue(planManager.areDependenciesMet("s2"))
    }

    @Test
    fun `areDependenciesMet returns false when one of multiple dependencies not done`() {
        val plan = AgentPlan(
            goal = "Test",
            steps = listOf(
                PlanStep(id = "s1", title = "First", status = "done"),
                PlanStep(id = "s2", title = "Second", status = "pending"),
                PlanStep(id = "s3", title = "Third", dependsOn = listOf("s1", "s2"))
            )
        )
        planManager.restorePlan(plan)
        assertFalse(planManager.areDependenciesMet("s3"))
    }

    @Test
    fun `areDependenciesMet returns true when no plan exists`() {
        assertTrue(planManager.areDependenciesMet("nonexistent"))
    }

    // --- Deviation Detection ---

    @Test
    fun `checkDeviation returns null when no plan`() {
        assertNull(planManager.checkDeviation("edit_file", "/src/Main.kt"))
    }

    @Test
    fun `checkDeviation returns null when no active step`() {
        val plan = AgentPlan(
            goal = "Test",
            steps = listOf(PlanStep(id = "s1", title = "Step 1", status = "pending", files = listOf("Main.kt")))
        )
        planManager.restorePlan(plan)
        assertNull(planManager.checkDeviation("edit_file", "/src/Main.kt"))
    }

    @Test
    fun `checkDeviation returns null when editing file in active step`() {
        val plan = AgentPlan(
            goal = "Test",
            steps = listOf(PlanStep(id = "s1", title = "Step 1", status = "running", files = listOf("Main.kt")))
        )
        planManager.restorePlan(plan)
        assertNull(planManager.checkDeviation("edit_file", "/src/Main.kt"))
    }

    @Test
    fun `checkDeviation returns warning when editing file NOT in active step`() {
        val plan = AgentPlan(
            goal = "Test",
            steps = listOf(PlanStep(id = "s1", title = "Step 1", status = "running", files = listOf("Main.kt")))
        )
        planManager.restorePlan(plan)
        val warning = planManager.checkDeviation("edit_file", "/src/Other.kt")
        assertNotNull(warning)
        assertTrue(warning!!.contains("Other.kt"))
        assertTrue(warning.contains("Step 1"))
    }

    @Test
    fun `checkDeviation returns null when active step has no files listed`() {
        val plan = AgentPlan(
            goal = "Test",
            steps = listOf(PlanStep(id = "s1", title = "Step 1", status = "running", files = emptyList()))
        )
        planManager.restorePlan(plan)
        assertNull(planManager.checkDeviation("edit_file", "/src/Any.kt"))
    }

    @Test
    fun `checkDeviation returns null when filePath is null`() {
        val plan = AgentPlan(
            goal = "Test",
            steps = listOf(PlanStep(id = "s1", title = "Step 1", status = "running", files = listOf("Main.kt")))
        )
        planManager.restorePlan(plan)
        assertNull(planManager.checkDeviation("run_command", null))
    }

    @Test
    fun `checkDeviation detects in_progress status`() {
        val plan = AgentPlan(
            goal = "Test",
            steps = listOf(PlanStep(id = "s1", title = "Step 1", status = "in_progress", files = listOf("Main.kt")))
        )
        planManager.restorePlan(plan)
        val warning = planManager.checkDeviation("edit_file", "/src/Other.kt")
        assertNotNull(warning)
    }

    // --- Approval Timeout ---

    @Test
    fun `submitPlanAndWait auto-approves on timeout`() = runTest {
        val plan = AgentPlan(
            goal = "Test",
            steps = listOf(PlanStep(id = "s1", title = "Step 1"))
        )
        val result = planManager.submitPlanAndWait(plan, timeoutMs = 100)
        assertTrue(result is PlanApprovalResult.Approved)
        assertTrue(planManager.isPlanApproved())
    }

    @Test
    fun `submitPlanAndWait returns approved when user approves before timeout`() = runTest {
        val plan = AgentPlan(
            goal = "Test",
            steps = listOf(PlanStep(id = "s1", title = "Step 1"))
        )
        launch {
            delay(50)
            planManager.approvePlan()
        }
        val result = planManager.submitPlanAndWait(plan, timeoutMs = 5000)
        assertTrue(result is PlanApprovalResult.Approved)
    }

    @Test
    fun `submitPlanAndWait returns revised when user revises before timeout`() = runTest {
        val plan = AgentPlan(
            goal = "Test",
            steps = listOf(PlanStep(id = "s1", title = "Step 1"))
        )
        launch {
            delay(50)
            planManager.revisePlan(mapOf("s1" to "Change approach"))
        }
        val result = planManager.submitPlanAndWait(plan, timeoutMs = 5000)
        assertTrue(result is PlanApprovalResult.Revised)
        assertEquals("Change approach", (result as PlanApprovalResult.Revised).comments["s1"])
    }

    // --- Revised Comments Persistence ---

    @Test
    fun `revisePlan persists comments to disk`() {
        val plan = AgentPlan(
            goal = "Test",
            steps = listOf(PlanStep(id = "s1", title = "Step 1"))
        )
        planManager.restorePlan(plan)
        planManager.revisePlan(mapOf("s1" to "Please use a different approach"))

        // Verify plan was saved to disk
        val savedPlan = PlanPersistence.load(tempDir.toFile())
        assertNotNull(savedPlan)
        assertEquals("Please use a different approach", savedPlan!!.steps.first().userComment)
    }

    // --- dependsOn serialization ---

    @Test
    fun `PlanStep with dependsOn serializes and deserializes correctly`() {
        val step = PlanStep(id = "s2", title = "Step 2", dependsOn = listOf("s1"))
        val json = PlanManager.json.encodeToString(PlanStep.serializer(), step)
        assertTrue(json.contains("dependsOn"))
        val deserialized = PlanManager.json.decodeFromString(PlanStep.serializer(), json)
        assertEquals(listOf("s1"), deserialized.dependsOn)
    }

    @Test
    fun `PlanStep without dependsOn defaults to empty list`() {
        val jsonStr = """{"id":"s1","title":"Step 1"}"""
        val step = PlanManager.json.decodeFromString(PlanStep.serializer(), jsonStr)
        assertEquals(emptyList<String>(), step.dependsOn)
    }

    // --- Existing functionality preserved ---

    @Test
    fun `submitPlan stores plan`() {
        val plan = AgentPlan(goal = "Test", steps = listOf(PlanStep(id = "s1", title = "Step 1")))
        planManager.restorePlan(plan)
        assertTrue(planManager.hasPlan())
        assertFalse(planManager.isPlanApproved())
    }

    @Test
    fun `approvePlan marks plan as approved`() {
        val plan = AgentPlan(goal = "Test", steps = listOf(PlanStep(id = "s1", title = "Step 1")))
        planManager.restorePlan(plan)
        planManager.approvePlan()
        assertTrue(planManager.isPlanApproved())
    }

    @Test
    fun `updateStepStatus changes step status`() {
        val plan = AgentPlan(goal = "Test", steps = listOf(PlanStep(id = "s1", title = "Step 1")))
        planManager.restorePlan(plan)
        planManager.updateStepStatus("s1", "done")
        assertEquals("done", planManager.currentPlan!!.steps.first().status)
    }

    @Test
    fun `clear resets state`() {
        val plan = AgentPlan(goal = "Test", steps = listOf(PlanStep(id = "s1", title = "Step 1")))
        planManager.restorePlan(plan)
        planManager.clear()
        assertFalse(planManager.hasPlan())
    }
}

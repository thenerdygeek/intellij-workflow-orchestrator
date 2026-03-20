package com.workflow.orchestrator.agent.runtime

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class PlanPersistenceTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `save writes plan json to session directory`() {
        val sessionDir = File(tempDir.toFile(), "session-1").apply { mkdirs() }
        val plan = AgentPlan(
            goal = "Fix NPE",
            approach = "Add null check",
            steps = listOf(PlanStep(id = "1", title = "Read file", files = listOf("Foo.kt"), action = "read")),
            testing = "Run tests"
        )
        PlanPersistence.save(plan, sessionDir)

        val planFile = File(sessionDir, "plan.json")
        assertTrue(planFile.exists())
        val loaded = Json { ignoreUnknownKeys = true }.decodeFromString<AgentPlan>(planFile.readText())
        assertEquals("Fix NPE", loaded.goal)
        assertEquals(1, loaded.steps.size)
    }

    @Test
    fun `load returns plan from session directory`() {
        val sessionDir = File(tempDir.toFile(), "session-2").apply { mkdirs() }
        val plan = AgentPlan(goal = "Test", steps = listOf(PlanStep(id = "1", title = "Step")))
        PlanPersistence.save(plan, sessionDir)

        val loaded = PlanPersistence.load(sessionDir)
        assertNotNull(loaded)
        assertEquals("Test", loaded!!.goal)
    }

    @Test
    fun `load returns null when no plan file exists`() {
        val sessionDir = File(tempDir.toFile(), "session-3").apply { mkdirs() }
        assertNull(PlanPersistence.load(sessionDir))
    }

    @Test
    fun `updateStepStatus updates step in persisted plan`() {
        val sessionDir = File(tempDir.toFile(), "session-4").apply { mkdirs() }
        val plan = AgentPlan(
            goal = "Test",
            steps = listOf(
                PlanStep(id = "1", title = "A", status = "pending"),
                PlanStep(id = "2", title = "B", status = "pending")
            )
        )
        PlanPersistence.save(plan, sessionDir)
        PlanPersistence.updateStepStatus(sessionDir, "1", "done")

        val loaded = PlanPersistence.load(sessionDir)!!
        assertEquals("done", loaded.steps.find { it.id == "1" }?.status)
        assertEquals("pending", loaded.steps.find { it.id == "2" }?.status)
    }
}

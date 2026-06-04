package com.workflow.orchestrator.agent.monitor

import com.workflow.orchestrator.core.model.bamboo.BuildJobData
import com.workflow.orchestrator.core.model.bamboo.BuildResultData
import com.workflow.orchestrator.core.model.bamboo.BuildStageData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BambooDiffTest {

    private fun build(
        state: String,
        lifeCycleState: String = "Finished",
        stages: List<BuildStageData> = emptyList(),
        testsPassed: Int = 0,
        testsFailed: Int = 0,
    ) = BuildResultData(
        planKey = "PROJ-PLAN",
        buildNumber = 42,
        state = state,
        durationSeconds = 10L,
        stages = stages,
        testsPassed = testsPassed,
        testsFailed = testsFailed,
        lifeCycleState = lifeCycleState,
    )

    private fun stage(name: String, state: String, vararg jobs: BuildJobData = emptyArray()) =
        BuildStageData(name = name, state = state, durationSeconds = 5L, jobs = jobs.toList())

    private fun job(name: String, state: String) =
        BuildJobData(name = name, state = state, durationSeconds = 3L, resultKey = "PROJ-PLAN-$name-42")

    // ---- BUILD level --------------------------------------------------------

    @Test
    fun `BUILD InProgress Unknown to Finished Failed emits one ALERT`() {
        val prev = build("Unknown", "InProgress")
        val cur = build("Failed", "Finished")
        val events = BambooDiff.diff("m1", BambooDiff.Level.BUILD, null, null, prev, cur)
        assertEquals(1, events.size)
        assertEquals(Severity.ALERT, events[0].severity)
    }

    @Test
    fun `BUILD InProgress to Finished Successful emits one NOTABLE`() {
        val prev = build("Unknown", "InProgress")
        val cur = build("Successful", "Finished")
        val events = BambooDiff.diff("m1", BambooDiff.Level.BUILD, null, null, prev, cur)
        assertEquals(1, events.size)
        assertEquals(Severity.NOTABLE, events[0].severity)
    }

    @Test
    fun `BUILD no state change emits nothing`() {
        val prev = build("Successful", "Finished")
        val cur = build("Successful", "Finished")
        val events = BambooDiff.diff("m1", BambooDiff.Level.BUILD, null, null, prev, cur)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `BUILD first poll with terminal Failed emits ALERT`() {
        val cur = build("Failed", "Finished")
        val events = BambooDiff.diff("m1", BambooDiff.Level.BUILD, null, null, null, cur)
        assertEquals(1, events.size)
        assertEquals(Severity.ALERT, events[0].severity)
    }

    @Test
    fun `BUILD first poll with InProgress emits nothing`() {
        val cur = build("Unknown", "InProgress")
        val events = BambooDiff.diff("m1", BambooDiff.Level.BUILD, null, null, null, cur)
        assertTrue(events.isEmpty())
    }

    // ---- STAGE level --------------------------------------------------------

    @Test
    fun `STAGE Build state Unknown to Failed emits ALERT`() {
        val prev = build("Unknown", "InProgress", listOf(stage("Build", "Unknown")))
        val cur = build("Failed", "Finished", listOf(stage("Build", "Failed")))
        val events = BambooDiff.diff("m1", BambooDiff.Level.STAGE, "Build", null, prev, cur)
        assertEquals(1, events.size)
        assertEquals(Severity.ALERT, events[0].severity)
    }

    @Test
    fun `STAGE unrelated stage change emits nothing for requested stageName`() {
        val prev = build("Unknown", "InProgress", listOf(stage("Build", "Unknown"), stage("Test", "Unknown")))
        val cur = build("Unknown", "InProgress", listOf(stage("Build", "Unknown"), stage("Test", "Failed")))
        // We requested "Build" which didn't change
        val events = BambooDiff.diff("m1", BambooDiff.Level.STAGE, "Build", null, prev, cur)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `STAGE stageName not present in current returns empty`() {
        val prev = build("Unknown", "InProgress", listOf(stage("Build", "Unknown")))
        val cur = build("Failed", "Finished", emptyList())
        val events = BambooDiff.diff("m1", BambooDiff.Level.STAGE, "Build", null, prev, cur)
        assertTrue(events.isEmpty())
    }

    // ---- JOB level ----------------------------------------------------------

    @Test
    fun `JOB Compile in stage Build Unknown to Failed emits ALERT`() {
        val prevStage = stage("Build", "Unknown", job("Compile", "Unknown"))
        val curStage = stage("Build", "Failed", job("Compile", "Failed"))
        val prev = build("Unknown", "InProgress", listOf(prevStage))
        val cur = build("Failed", "Finished", listOf(curStage))
        val events = BambooDiff.diff("m1", BambooDiff.Level.JOB, "Build", "Compile", prev, cur)
        assertEquals(1, events.size)
        assertEquals(Severity.ALERT, events[0].severity)
    }

    @Test
    fun `JOB job not found returns empty`() {
        val curStage = stage("Build", "Failed", job("OtherJob", "Failed"))
        val cur = build("Failed", "Finished", listOf(curStage))
        val events = BambooDiff.diff("m1", BambooDiff.Level.JOB, "Build", "Compile", null, cur)
        assertTrue(events.isEmpty())
    }
}

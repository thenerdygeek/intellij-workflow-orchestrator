package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.WorkerType
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PlanModeRespondToolTest {

    private val project = mockk<Project>(relaxed = true)
    private val tool = PlanModeRespondTool()

    @Test
    fun `tool name is plan_mode_respond`() {
        assertEquals("plan_mode_respond", tool.name)
    }

    @Test
    fun `response is required parameter`() {
        assertTrue(tool.parameters.required.contains("response"))
        assertFalse(tool.parameters.required.contains("needs_more_exploration"))
        assertFalse(tool.parameters.required.contains("task_progress"))
    }

    @Test
    fun `allowedWorkers contains only ORCHESTRATOR`() {
        assertEquals(setOf(WorkerType.ORCHESTRATOR), tool.allowedWorkers)
    }

    @Test
    fun `returns plan text with isPlanResponse flag`() = runTest {
        val result = tool.execute(buildJsonObject {
            put("response", "Here is my plan: Step 1 read files, Step 2 edit code")
        }, project)

        assertTrue(result.isPlanResponse, "should have isPlanResponse=true")
        assertFalse(result.isError)
        assertFalse(result.isCompletion)
        assertFalse(result.needsMoreExploration)
        assertEquals("Here is my plan: Step 1 read files, Step 2 edit code", result.content)
    }

    @Test
    fun `needs_more_exploration defaults to false`() = runTest {
        val result = tool.execute(buildJsonObject {
            put("response", "My plan so far")
        }, project)

        assertFalse(result.needsMoreExploration)
        assertTrue(result.isPlanResponse)
    }

    @Test
    fun `needs_more_exploration=true is respected`() = runTest {
        val result = tool.execute(buildJsonObject {
            put("response", "I need to explore more files before I can finalize")
            put("needs_more_exploration", true)
        }, project)

        assertTrue(result.needsMoreExploration)
        assertTrue(result.isPlanResponse)
    }

    @Test
    fun `needs_more_exploration=false is respected`() = runTest {
        val result = tool.execute(buildJsonObject {
            put("response", "Complete plan ready")
            put("needs_more_exploration", false)
        }, project)

        assertFalse(result.needsMoreExploration)
        assertTrue(result.isPlanResponse)
    }

    @Test
    fun `missing response returns error`() = runTest {
        val result = tool.execute(buildJsonObject {
            put("needs_more_exploration", true)
        }, project)

        assertTrue(result.isError)
        assertFalse(result.isPlanResponse)
    }

    @Test
    fun `description mentions plan mode and exploration`() {
        assertTrue(tool.description.contains("PLAN MODE"), "description should mention PLAN MODE")
        assertTrue(tool.description.contains("concrete plan"), "description should mention concrete plan")
        assertTrue(tool.description.contains("needs_more_exploration"), "description should mention needs_more_exploration")
    }
}

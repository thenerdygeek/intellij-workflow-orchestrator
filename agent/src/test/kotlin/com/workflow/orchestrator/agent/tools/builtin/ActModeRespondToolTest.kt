package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.WorkerType
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ActModeRespondToolTest {

    private val project = mockk<Project>(relaxed = true)
    private val tool = ActModeRespondTool()

    @Test
    fun `tool name is act_mode_respond`() {
        assertEquals("act_mode_respond", tool.name)
    }

    @Test
    fun `response is required parameter`() {
        assertTrue(tool.parameters.required.contains("response"))
    }

    @Test
    fun `allowedWorkers contains only ORCHESTRATOR`() {
        assertEquals(setOf(WorkerType.ORCHESTRATOR), tool.allowedWorkers)
    }

    @Test
    fun `returns progress text without completion flag`() = runTest {
        val result = tool.execute(buildJsonObject {
            put("response", "I found the relevant files, now editing...")
        }, project)

        assertFalse(result.isCompletion, "should NOT be a completion")
        assertFalse(result.isError)
        assertFalse(result.isPlanResponse, "should NOT be a plan response")
        assertEquals("I found the relevant files, now editing...", result.content)
    }

    @Test
    fun `missing response returns error`() = runTest {
        val result = tool.execute(buildJsonObject {}, project)

        assertTrue(result.isError)
    }

    @Test
    fun `description mentions ACT MODE and progress`() {
        assertTrue(tool.description.contains("ACT MODE"), "description should mention ACT MODE")
        assertTrue(tool.description.contains("progress"), "description should mention progress")
        assertTrue(tool.description.contains("consecutively"), "description should mention consecutive constraint")
    }
}

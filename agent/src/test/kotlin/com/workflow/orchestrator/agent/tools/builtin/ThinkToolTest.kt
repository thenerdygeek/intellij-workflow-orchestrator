package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ThinkToolTest {
    private val project = mockk<Project>(relaxed = true)
    private val tool = ThinkTool()

    @Test
    fun `tool metadata is correct`() {
        assertEquals("think", tool.name)
        assertTrue(tool.parameters.required.contains("thought"))
    }

    @Test
    fun `execute returns thought recorded`() = runTest {
        val params = buildJsonObject { put("thought", "I should read the file first") }
        val result = tool.execute(params, project)
        assertFalse(result.isError)
        assertTrue(result.content.contains("Thought recorded"))
        assertTrue(result.tokenEstimate <= 5)
    }

    @Test
    fun `returns error when thought missing`() = runTest {
        val result = tool.execute(buildJsonObject { }, project)
        assertTrue(result.isError)
    }

    @Test
    fun `allowed for all worker types`() {
        assertEquals(5, tool.allowedWorkers.size)
    }
}

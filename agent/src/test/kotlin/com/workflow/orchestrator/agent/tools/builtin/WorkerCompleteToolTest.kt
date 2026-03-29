package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.ToolResult
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class WorkerCompleteToolTest {

    private val project = mockk<Project>(relaxed = true)
    private val tool = WorkerCompleteTool()

    @Test
    fun `valid result param returns isCompletion=true and isError=false`() = runTest {
        val resultText = "Edited Foo.kt at line 42 to fix NPE. Ran diagnostics — no errors."
        val params = buildJsonObject { put("result", resultText) }

        val result = tool.execute(params, project)

        assertTrue(result.isCompletion)
        assertFalse(result.isError)
        assertEquals(resultText, result.content)
    }

    @Test
    fun `missing result param returns isError=true and isCompletion=false`() = runTest {
        val params = buildJsonObject { }

        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertFalse(result.isCompletion)
        assertTrue(result.content.contains("Missing required parameter: result"))
        assertEquals(ToolResult.ERROR_TOKEN_ESTIMATE, result.tokenEstimate)
    }

    @Test
    fun `allowedWorkers contains all 5 WorkerType values`() {
        val expected = WorkerType.values().toSet()
        assertEquals(expected, tool.allowedWorkers)
    }

    @Test
    fun `allowedWorkers has exactly 5 entries`() {
        assertEquals(5, tool.allowedWorkers.size)
    }

    @Test
    fun `token estimate is based on result length`() = runTest {
        val resultText = "A".repeat(400)
        val params = buildJsonObject { put("result", resultText) }

        val result = tool.execute(params, project)

        assertEquals(100, result.tokenEstimate) // 400 / 4 = 100
    }

    @Test
    fun `summary is truncated to 200 chars for long results`() = runTest {
        val longResult = "B".repeat(300)
        val params = buildJsonObject { put("result", longResult) }

        val result = tool.execute(params, project)

        assertTrue(result.summary.contains("B".repeat(200)))
        assertFalse(result.summary.contains("B".repeat(201)))
    }
}

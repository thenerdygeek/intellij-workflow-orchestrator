package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.runtime.CompletionGatekeeper
import com.workflow.orchestrator.agent.runtime.WorkerType
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AttemptCompletionToolTest {

    private val project = mockk<Project>(relaxed = true)
    private val gatekeeper = mockk<CompletionGatekeeper>(relaxed = true)
    private val tool = AttemptCompletionTool(gatekeeper)

    @Test
    fun `tool name is attempt_completion`() {
        assertEquals("attempt_completion", tool.name)
    }

    @Test
    fun `result is required parameter`() {
        assertTrue(tool.parameters.required.contains("result"))
        assertFalse(tool.parameters.required.contains("command"))
    }

    @Test
    fun `allowedWorkers contains only ORCHESTRATOR`() {
        assertEquals(setOf(WorkerType.ORCHESTRATOR), tool.allowedWorkers)
        assertFalse(tool.allowedWorkers.contains(WorkerType.CODER))
        assertFalse(tool.allowedWorkers.contains(WorkerType.ANALYZER))
        assertFalse(tool.allowedWorkers.contains(WorkerType.REVIEWER))
        assertFalse(tool.allowedWorkers.contains(WorkerType.TOOLER))
    }

    @Test
    fun `returns completion result when all gates pass`() = runTest {
        every { gatekeeper.checkCompletion() } returns null

        val params = buildJsonObject { put("result", "All tests pass and feature is complete") }
        val result = tool.execute(params, project)

        assertFalse(result.isError)
        assertTrue(result.isCompletion)
        assertEquals("All tests pass and feature is complete", result.content)
        assertTrue(result.summary.startsWith("Task completed:"))
        assertNull(result.verifyCommand)
    }

    @Test
    fun `returns completion with verifyCommand when command param provided`() = runTest {
        every { gatekeeper.checkCompletion() } returns null

        val params = buildJsonObject {
            put("result", "Implementation done")
            put("command", "./gradlew :agent:test")
        }
        val result = tool.execute(params, project)

        assertFalse(result.isError)
        assertTrue(result.isCompletion)
        assertEquals("./gradlew :agent:test", result.verifyCommand)
    }

    @Test
    fun `returns error when gate blocks completion`() = runTest {
        val blockMessage = "COMPLETION BLOCKED: Your plan has 2 incomplete steps:\n1. [pending] Write tests\n2. [pending] Run tests"
        every { gatekeeper.checkCompletion() } returns blockMessage

        val params = buildJsonObject { put("result", "Done") }
        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertFalse(result.isCompletion)
        assertEquals(blockMessage, result.content)
        assertEquals("Completion blocked by gate", result.summary)
    }

    @Test
    fun `returns error when result parameter is missing`() = runTest {
        val params = buildJsonObject { }
        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertFalse(result.isCompletion)
        assertTrue(result.content.contains("Missing required parameter: result"))
        assertEquals(result.tokenEstimate, com.workflow.orchestrator.agent.tools.ToolResult.ERROR_TOKEN_ESTIMATE)
    }

    @Test
    fun `summary is truncated to 200 chars for long results`() = runTest {
        every { gatekeeper.checkCompletion() } returns null

        val longResult = "A".repeat(300)
        val params = buildJsonObject { put("result", longResult) }
        val result = tool.execute(params, project)

        assertFalse(result.isError)
        assertTrue(result.isCompletion)
        // summary takes first 200 chars of result
        assertTrue(result.summary.contains("A".repeat(200)))
        assertFalse(result.summary.contains("A".repeat(201)))
    }

    @Test
    fun `token estimate is based on result length`() = runTest {
        every { gatekeeper.checkCompletion() } returns null

        val resultText = "A".repeat(400)
        val params = buildJsonObject { put("result", resultText) }
        val toolResult = tool.execute(params, project)

        assertEquals(100, toolResult.tokenEstimate) // 400 / 4 = 100
    }

    @Test
    fun `verifyCommand is null when command param not provided`() = runTest {
        every { gatekeeper.checkCompletion() } returns null

        val params = buildJsonObject { put("result", "Done") }
        val result = tool.execute(params, project)

        assertNull(result.verifyCommand)
    }
}

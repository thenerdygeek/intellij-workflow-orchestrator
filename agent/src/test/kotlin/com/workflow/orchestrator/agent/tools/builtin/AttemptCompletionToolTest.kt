package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.WorkerType
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AttemptCompletionToolTest {

    private val project = mockk<Project>(relaxed = true)
    private val tool = AttemptCompletionTool()

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
    }

    @Test
    fun `returns completion result with isCompletion=true`() = runTest {
        val result = tool.execute(buildJsonObject {
            put("result", "Task done")
        }, project)
        assertTrue(result.isCompletion)
        assertFalse(result.isError)
        assertEquals("Task done", result.content)
    }

    @Test
    fun `missing result returns error`() = runTest {
        val result = tool.execute(buildJsonObject {}, project)
        assertTrue(result.isError)
        assertFalse(result.isCompletion)
    }

    @Test
    fun `includes verify command when provided`() = runTest {
        val result = tool.execute(buildJsonObject {
            put("result", "Done")
            put("command", "./gradlew test")
        }, project)
        assertEquals("./gradlew test", result.completionData?.verifyHow)
    }
}

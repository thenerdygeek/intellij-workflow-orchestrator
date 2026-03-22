package com.workflow.orchestrator.agent.tools.runtime

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.runtime.WorkerType
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class GetRunningProcessesToolTest {
    private val tool = GetRunningProcessesTool()

    @Test
    fun `tool metadata is correct`() {
        assertEquals("get_running_processes", tool.name)
        assertTrue(tool.parameters.properties.isEmpty())
        assertTrue(tool.parameters.required.isEmpty())
        assertTrue(tool.description.contains("run/debug sessions"))
    }

    @Test
    fun `allowedWorkers includes CODER, REVIEWER, ANALYZER`() {
        assertEquals(
            setOf(WorkerType.CODER, WorkerType.REVIEWER, WorkerType.ANALYZER),
            tool.allowedWorkers
        )
    }

    @Test
    fun `toToolDefinition produces valid schema`() {
        val def = tool.toToolDefinition()
        assertEquals("function", def.type)
        assertEquals("get_running_processes", def.function.name)
        assertTrue(def.function.description.isNotBlank())
        assertEquals("object", def.function.parameters.type)
        assertTrue(def.function.parameters.properties.isEmpty())
    }

    @Test
    fun `takes no parameters`() {
        assertTrue(tool.parameters.properties.isEmpty())
        assertTrue(tool.parameters.required.isEmpty())
    }

    @Test
    fun `execute handles missing ExecutionManager gracefully`() = runTest {
        val project = mockk<Project>(relaxed = true)

        val result = tool.execute(buildJsonObject {}, project)

        // Without a running IDE, ExecutionManager.getInstance() will throw — tool should handle gracefully
        assertTrue(result.isError)
        assertTrue(result.content.contains("Error"))
    }

    @Test
    fun `description mentions active sessions`() {
        assertTrue(tool.description.contains("active"))
    }
}

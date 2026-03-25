package com.workflow.orchestrator.agent.tools.debug

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.runtime.WorkerType
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ThreadDumpToolTest {
    private lateinit var controller: AgentDebugController
    private lateinit var tool: ThreadDumpTool
    private val mockProject = mockk<Project>(relaxed = true)

    @BeforeEach
    fun setUp() {
        controller = AgentDebugController(mockProject)
        tool = ThreadDumpTool(controller)
    }

    @Test
    fun `tool metadata is correct`() {
        assertEquals("thread_dump", tool.name)
        assertTrue(tool.description.contains("thread dump"))
        assertTrue(tool.description.contains("RUNNING"))
        assertTrue(tool.description.contains("BLOCKED"))
        assertTrue(tool.description.contains("WAITING"))
        assertTrue(tool.description.contains("deadlock"))
    }

    @Test
    fun `parameters are all optional`() {
        assertTrue(tool.parameters.required.isEmpty())
    }

    @Test
    fun `has all four parameters`() {
        val props = tool.parameters.properties
        assertEquals(4, props.size)
        assertTrue(props.containsKey("session_id"))
        assertTrue(props.containsKey("include_stacks"))
        assertTrue(props.containsKey("max_frames"))
        assertTrue(props.containsKey("include_daemon"))
    }

    @Test
    fun `session_id parameter is string type`() {
        assertEquals("string", tool.parameters.properties["session_id"]?.type)
    }

    @Test
    fun `include_stacks parameter is boolean type`() {
        assertEquals("boolean", tool.parameters.properties["include_stacks"]?.type)
    }

    @Test
    fun `max_frames parameter is integer type`() {
        assertEquals("integer", tool.parameters.properties["max_frames"]?.type)
    }

    @Test
    fun `include_daemon parameter is boolean type`() {
        assertEquals("boolean", tool.parameters.properties["include_daemon"]?.type)
    }

    @Test
    fun `allowedWorkers includes CODER and ANALYZER`() {
        assertEquals(setOf(WorkerType.CODER, WorkerType.ANALYZER), tool.allowedWorkers)
    }

    @Test
    fun `toToolDefinition produces valid schema`() {
        val def = tool.toToolDefinition()
        assertEquals("function", def.type)
        assertEquals("thread_dump", def.function.name)
        assertTrue(def.function.description.isNotBlank())
        assertEquals("object", def.function.parameters.type)
        assertEquals(4, def.function.parameters.properties.size)
        assertTrue(def.function.parameters.required.isEmpty())
    }

    @Test
    fun `execute returns error when no session exists`() = runTest {
        val params = buildJsonObject {}

        val result = tool.execute(params, mockProject)

        assertTrue(result.isError)
        assertTrue(result.content.contains("No debug session found"))
        assertTrue(result.content.contains("start_debug_session"))
    }

    @Test
    fun `execute returns error with specific session_id not found`() = runTest {
        val params = buildJsonObject {
            put("session_id", "debug-999")
        }

        val result = tool.execute(params, mockProject)

        assertTrue(result.isError)
        assertTrue(result.content.contains("No debug session found"))
        assertTrue(result.content.contains("debug-999"))
    }

    @Test
    fun `statusToString maps all thread status constants`() {
        assertEquals("RUNNING", ThreadDumpTool.statusToString(1))
        assertEquals("SLEEPING", ThreadDumpTool.statusToString(2))
        assertEquals("BLOCKED", ThreadDumpTool.statusToString(3))
        assertEquals("WAITING", ThreadDumpTool.statusToString(4))
        assertEquals("NOT_STARTED", ThreadDumpTool.statusToString(5))
        assertEquals("TERMINATED", ThreadDumpTool.statusToString(0))
        assertEquals("UNKNOWN", ThreadDumpTool.statusToString(-1))
        assertEquals("UNKNOWN", ThreadDumpTool.statusToString(42))
    }

    @Test
    fun `description mentions debug session requirement`() {
        assertTrue(tool.description.contains("debug session"))
    }

    @Test
    fun `description mentions concurrency`() {
        assertTrue(tool.description.contains("concurrency"))
    }
}

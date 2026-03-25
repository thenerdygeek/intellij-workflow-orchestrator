package com.workflow.orchestrator.agent.tools.debug

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.runtime.WorkerType
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MemoryViewToolTest {
    private val controller = mockk<AgentDebugController>(relaxed = true)
    private val project = mockk<Project>(relaxed = true)
    private val tool = MemoryViewTool(controller)

    @Test
    fun `metadata is correct`() {
        assertEquals("memory_view", tool.name)
        assertTrue(tool.description.contains("Count live instances"))
        assertTrue(tool.description.contains("memory leaks"))
    }

    @Test
    fun `class_name is required`() {
        assertEquals(listOf("class_name"), tool.parameters.required)
    }

    @Test
    fun `has session_id, class_name, and max_instances parameters`() {
        val props = tool.parameters.properties
        assertEquals(3, props.size)
        assertTrue(props.containsKey("session_id"))
        assertTrue(props.containsKey("class_name"))
        assertTrue(props.containsKey("max_instances"))
        assertEquals("string", props["class_name"]?.type)
        assertEquals("integer", props["max_instances"]?.type)
    }

    @Test
    fun `allowed workers include ANALYZER and CODER`() {
        assertEquals(setOf(WorkerType.ANALYZER, WorkerType.CODER), tool.allowedWorkers)
    }

    @Test
    fun `returns error when class_name is missing`() = runTest {
        val result = tool.execute(buildJsonObject { }, project)
        assertTrue(result.isError)
        assertTrue(result.content.contains("Missing required parameter: class_name"))
    }

    @Test
    fun `returns error when no session found`() = runTest {
        every { controller.getActiveSessionId() } returns null
        every { controller.getSession(null) } returns null

        val result = tool.execute(buildJsonObject {
            put("class_name", "java.lang.String")
        }, project)
        assertTrue(result.isError)
        assertTrue(result.content.contains("No debug session found"))
    }

    @Test
    fun `returns error when specified session not found`() = runTest {
        every { controller.getSession("bad-id") } returns null

        val result = tool.execute(buildJsonObject {
            put("session_id", "bad-id")
            put("class_name", "java.lang.String")
        }, project)
        assertTrue(result.isError)
        assertTrue(result.content.contains("No debug session found"))
        assertTrue(result.content.contains("bad-id"))
    }

    @Test
    fun `produces valid tool definition`() {
        val def = tool.toToolDefinition()
        assertEquals("function", def.type)
        assertEquals("memory_view", def.function.name)
        assertTrue(def.function.description.isNotBlank())
        assertEquals("object", def.function.parameters.type)
        assertEquals(listOf("class_name"), def.function.parameters.required)
    }
}

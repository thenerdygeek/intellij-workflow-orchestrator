package com.workflow.orchestrator.agent.tools.debug

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.runtime.WorkerType
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AttachToProcessToolTest {
    private val controller = mockk<AgentDebugController>(relaxed = true)
    private val project = mockk<Project>(relaxed = true)
    private val tool = AttachToProcessTool(controller)

    @Test
    fun `metadata is correct`() {
        assertEquals("attach_to_process", tool.name)
        assertTrue(tool.description.contains("Attach the debugger"))
        assertTrue(tool.description.contains("running JVM"))
    }

    @Test
    fun `port and description are required`() {
        assertEquals(listOf("port", "description"), tool.parameters.required)
    }

    @Test
    fun `has host, port, name, and description parameters`() {
        val props = tool.parameters.properties
        assertEquals(4, props.size)
        assertTrue(props.containsKey("host"))
        assertTrue(props.containsKey("port"))
        assertTrue(props.containsKey("name"))
        assertTrue(props.containsKey("description"))
        assertEquals("string", props["host"]?.type)
        assertEquals("integer", props["port"]?.type)
        assertEquals("string", props["name"]?.type)
        assertEquals("string", props["description"]?.type)
    }

    @Test
    fun `allowed workers include CODER only`() {
        assertEquals(setOf(WorkerType.CODER), tool.allowedWorkers)
    }

    @Test
    fun `returns error when port is missing`() = runTest {
        val result = tool.execute(buildJsonObject {
            put("description", "attach to server")
        }, project)
        assertTrue(result.isError)
        assertTrue(result.content.contains("Missing or invalid required parameter: port"))
    }

    @Test
    fun `returns error for invalid port below range`() = runTest {
        val result = tool.execute(buildJsonObject {
            put("port", 0)
            put("description", "test")
        }, project)
        assertTrue(result.isError)
        assertTrue(result.content.contains("Port must be between 1 and 65535"))
    }

    @Test
    fun `returns error for invalid port above range`() = runTest {
        val result = tool.execute(buildJsonObject {
            put("port", 70000)
            put("description", "test")
        }, project)
        assertTrue(result.isError)
        assertTrue(result.content.contains("Port must be between 1 and 65535"))
    }

    @Test
    fun `produces valid tool definition`() {
        val def = tool.toToolDefinition()
        assertEquals("function", def.type)
        assertEquals("attach_to_process", def.function.name)
        assertTrue(def.function.description.isNotBlank())
        assertEquals("object", def.function.parameters.type)
        assertEquals(listOf("port", "description"), def.function.parameters.required)
    }
}

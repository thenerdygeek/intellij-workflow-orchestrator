package com.workflow.orchestrator.agent.tools.debug

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.runtime.WorkerType
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MethodBreakpointToolTest {
    private val controller = mockk<AgentDebugController>(relaxed = true)
    private val tool = MethodBreakpointTool(controller)

    @Test
    fun `tool name is method_breakpoint`() {
        assertEquals("method_breakpoint", tool.name)
    }

    @Test
    fun `description contains performance warning`() {
        assertTrue(tool.description.contains("slower"))
        assertTrue(tool.description.contains("5-10x"))
        assertTrue(tool.description.contains("sparingly"))
    }

    @Test
    fun `description mentions interface method support`() {
        assertTrue(tool.description.contains("interface"))
        assertTrue(tool.description.contains("implementations"))
    }

    @Test
    fun `required parameters are class_name and method_name`() {
        assertEquals(listOf("class_name", "method_name"), tool.parameters.required)
    }

    @Test
    fun `has all five parameters`() {
        val props = tool.parameters.properties
        assertEquals(5, props.size)
        assertTrue(props.containsKey("class_name"))
        assertTrue(props.containsKey("method_name"))
        assertTrue(props.containsKey("file"))
        assertTrue(props.containsKey("watch_entry"))
        assertTrue(props.containsKey("watch_exit"))
    }

    @Test
    fun `class_name parameter is string type`() {
        assertEquals("string", tool.parameters.properties["class_name"]?.type)
    }

    @Test
    fun `method_name parameter is string type`() {
        assertEquals("string", tool.parameters.properties["method_name"]?.type)
    }

    @Test
    fun `file parameter is string type`() {
        assertEquals("string", tool.parameters.properties["file"]?.type)
    }

    @Test
    fun `watch_entry parameter is boolean type`() {
        assertEquals("boolean", tool.parameters.properties["watch_entry"]?.type)
    }

    @Test
    fun `watch_exit parameter is boolean type`() {
        assertEquals("boolean", tool.parameters.properties["watch_exit"]?.type)
    }

    @Test
    fun `allowedWorkers includes CODER only`() {
        assertEquals(setOf(WorkerType.CODER), tool.allowedWorkers)
    }

    @Test
    fun `toToolDefinition produces valid schema`() {
        val def = tool.toToolDefinition()
        assertEquals("function", def.type)
        assertEquals("method_breakpoint", def.function.name)
        assertTrue(def.function.description.isNotBlank())
        assertEquals("object", def.function.parameters.type)
        assertEquals(5, def.function.parameters.properties.size)
        assertEquals(listOf("class_name", "method_name"), def.function.parameters.required)
    }

    @Test
    fun `execute returns error when class_name is missing`() = runTest {
        val project = mockk<Project>(relaxed = true)
        val params = buildJsonObject {
            put("method_name", "doSomething")
        }

        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("Missing required parameter: class_name"))
    }

    @Test
    fun `execute returns error when method_name is missing`() = runTest {
        val project = mockk<Project>(relaxed = true)
        val params = buildJsonObject {
            put("class_name", "com.example.MyService")
        }

        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("Missing required parameter: method_name"))
    }

    @Test
    fun `execute returns error when both watch flags are false`() = runTest {
        val project = mockk<Project>(relaxed = true)
        val params = buildJsonObject {
            put("class_name", "com.example.MyService")
            put("method_name", "doSomething")
            put("watch_entry", false)
            put("watch_exit", false)
        }

        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("never trigger"))
    }

    @Test
    fun `execute handles missing PSI gracefully`() = runTest {
        val project = mockk<Project>(relaxed = true)
        val params = buildJsonObject {
            put("class_name", "com.example.MyService")
            put("method_name", "doSomething")
        }

        // Without a running IDE, JavaPsiFacade.getInstance() will throw
        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("Error"))
    }

    @Test
    fun `execute handles watch_entry only`() = runTest {
        val project = mockk<Project>(relaxed = true)
        val params = buildJsonObject {
            put("class_name", "com.example.MyService")
            put("method_name", "doSomething")
            put("watch_entry", true)
            put("watch_exit", false)
        }

        // Without IDE, will get an exception but should not fail on validation
        val result = tool.execute(params, project)

        // Validates that watch_entry=true, watch_exit=false passes validation
        // (fails at PSI level since no running IDE)
        assertTrue(result.isError)
        assertFalse(result.content.contains("never trigger"))
    }

    @Test
    fun `execute handles watch_exit only`() = runTest {
        val project = mockk<Project>(relaxed = true)
        val params = buildJsonObject {
            put("class_name", "com.example.MyService")
            put("method_name", "doSomething")
            put("watch_entry", false)
            put("watch_exit", true)
        }

        val result = tool.execute(params, project)

        // Should not fail on validation — only at PSI/IDE level
        assertTrue(result.isError)
        assertFalse(result.content.contains("never trigger"))
    }

    @Test
    fun `execute defaults watch_entry to true and watch_exit to false`() = runTest {
        val project = mockk<Project>(relaxed = true)
        val params = buildJsonObject {
            put("class_name", "com.example.MyService")
            put("method_name", "doSomething")
        }

        // Should not fail on "never trigger" — defaults are entry=true, exit=false
        val result = tool.execute(params, project)

        assertTrue(result.isError) // Fails at PSI level
        assertFalse(result.content.contains("never trigger"))
    }

    @Test
    fun `description mentions entry and exit`() {
        assertTrue(tool.description.contains("entry"))
        assertTrue(tool.description.contains("exit"))
    }

    @Test
    fun `watch_entry description mentions default true`() {
        val desc = tool.parameters.properties["watch_entry"]?.description ?: ""
        assertTrue(desc.contains("true"))
    }

    @Test
    fun `watch_exit description mentions default false`() {
        val desc = tool.parameters.properties["watch_exit"]?.description ?: ""
        assertTrue(desc.contains("false"))
    }
}

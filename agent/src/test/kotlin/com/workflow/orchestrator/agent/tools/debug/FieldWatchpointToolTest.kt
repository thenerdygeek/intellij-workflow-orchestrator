package com.workflow.orchestrator.agent.tools.debug

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.runtime.WorkerType
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FieldWatchpointToolTest {
    private val controller = mockk<AgentDebugController>(relaxed = true)
    private val tool = FieldWatchpointTool(controller)

    @Test
    fun `tool name is field_watchpoint`() {
        assertEquals("field_watchpoint", tool.name)
    }

    @Test
    fun `description mentions watchpoint and field read write`() {
        assertTrue(tool.description.contains("watchpoint"))
        assertTrue(tool.description.contains("read"))
        assertTrue(tool.description.contains("written"))
    }

    @Test
    fun `description mentions performance impact`() {
        assertTrue(tool.description.contains("performance impact"))
        assertTrue(tool.description.contains("faster than method breakpoints"))
        assertTrue(tool.description.contains("slower than line breakpoints"))
    }

    @Test
    fun `required parameters are class_name and field_name`() {
        assertEquals(listOf("class_name", "field_name"), tool.parameters.required)
    }

    @Test
    fun `has all five parameters`() {
        val props = tool.parameters.properties
        assertEquals(5, props.size)
        assertTrue(props.containsKey("class_name"))
        assertTrue(props.containsKey("field_name"))
        assertTrue(props.containsKey("file"))
        assertTrue(props.containsKey("watch_read"))
        assertTrue(props.containsKey("watch_write"))
    }

    @Test
    fun `class_name parameter is string type`() {
        assertEquals("string", tool.parameters.properties["class_name"]?.type)
    }

    @Test
    fun `field_name parameter is string type`() {
        assertEquals("string", tool.parameters.properties["field_name"]?.type)
    }

    @Test
    fun `file parameter is string type`() {
        assertEquals("string", tool.parameters.properties["file"]?.type)
    }

    @Test
    fun `watch_read parameter is boolean type`() {
        assertEquals("boolean", tool.parameters.properties["watch_read"]?.type)
    }

    @Test
    fun `watch_write parameter is boolean type`() {
        assertEquals("boolean", tool.parameters.properties["watch_write"]?.type)
    }

    @Test
    fun `allowedWorkers includes CODER only`() {
        assertEquals(setOf(WorkerType.CODER), tool.allowedWorkers)
    }

    @Test
    fun `toToolDefinition produces valid schema`() {
        val def = tool.toToolDefinition()
        assertEquals("function", def.type)
        assertEquals("field_watchpoint", def.function.name)
        assertTrue(def.function.description.isNotBlank())
        assertEquals("object", def.function.parameters.type)
        assertEquals(5, def.function.parameters.properties.size)
        assertEquals(listOf("class_name", "field_name"), def.function.parameters.required)
    }

    @Test
    fun `execute returns error when class_name is missing`() = runTest {
        val project = mockk<Project>(relaxed = true)
        val params = buildJsonObject {
            put("field_name", "count")
        }

        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("Missing required parameter: class_name"))
    }

    @Test
    fun `execute returns error when field_name is missing`() = runTest {
        val project = mockk<Project>(relaxed = true)
        val params = buildJsonObject {
            put("class_name", "com.example.MyClass")
        }

        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("Missing required parameter: field_name"))
    }

    @Test
    fun `execute returns error when both watch flags are false`() = runTest {
        val project = mockk<Project>(relaxed = true)
        val params = buildJsonObject {
            put("class_name", "com.example.MyClass")
            put("field_name", "count")
            put("watch_read", false)
            put("watch_write", false)
        }

        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("will never trigger"))
    }

    @Test
    fun `execute returns error when both params missing`() = runTest {
        val project = mockk<Project>(relaxed = true)
        val params = buildJsonObject {}

        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("Missing required parameter"))
    }

    @Test
    fun `class_name description mentions FQN`() {
        val desc = tool.parameters.properties["class_name"]?.description ?: ""
        assertTrue(desc.contains("com.example"))
    }

    @Test
    fun `watch_read description mentions default false`() {
        val desc = tool.parameters.properties["watch_read"]?.description ?: ""
        assertTrue(desc.contains("false"))
    }

    @Test
    fun `watch_write description mentions default true`() {
        val desc = tool.parameters.properties["watch_write"]?.description ?: ""
        assertTrue(desc.contains("true"))
    }
}

package com.workflow.orchestrator.agent.tools.runtime

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.runtime.WorkerType
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class GetRunOutputToolTest {
    private val tool = GetRunOutputTool()

    @Test
    fun `tool metadata is correct`() {
        assertEquals("get_run_output", tool.name)
        assertTrue(tool.parameters.properties.containsKey("config_name"))
        assertTrue(tool.parameters.properties.containsKey("last_n_lines"))
        assertTrue(tool.parameters.properties.containsKey("filter"))
        assertEquals(listOf("config_name"), tool.parameters.required)
        assertTrue(tool.description.contains("console output"))
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
        assertEquals("get_run_output", def.function.name)
        assertTrue(def.function.description.isNotBlank())
        assertEquals("object", def.function.parameters.type)
        assertTrue(def.function.parameters.required.contains("config_name"))
        assertEquals("string", def.function.parameters.properties["config_name"]?.type)
        assertEquals("integer", def.function.parameters.properties["last_n_lines"]?.type)
        assertEquals("string", def.function.parameters.properties["filter"]?.type)
    }

    @Test
    fun `returns error when config_name is missing`() = runTest {
        val project = mockk<Project>(relaxed = true)

        val result = tool.execute(buildJsonObject {}, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("'config_name' required"))
    }

    @Test
    fun `returns error for invalid regex filter`() = runTest {
        val project = mockk<Project>(relaxed = true)

        val result = tool.execute(buildJsonObject {
            put("config_name", JsonPrimitive("MyApp"))
            put("filter", JsonPrimitive("[invalid"))
        }, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("invalid regex pattern"))
    }

    @Test
    fun `execute handles missing RunContentManager gracefully`() = runTest {
        val project = mockk<Project>(relaxed = true)

        val result = tool.execute(buildJsonObject {
            put("config_name", JsonPrimitive("MyApp"))
        }, project)

        // Without a running IDE, RunContentManager.getInstance() will throw — tool should handle gracefully
        assertTrue(result.isError)
    }

    @Test
    fun `last_n_lines parameter description mentions max`() {
        val prop = tool.parameters.properties["last_n_lines"]
        assertNotNull(prop)
        assertTrue(prop!!.description.contains("1000"))
    }

    @Test
    fun `config_name is required`() {
        assertTrue(tool.parameters.required.contains("config_name"))
        assertEquals(1, tool.parameters.required.size)
    }

    @Test
    fun `filter parameter description mentions regex`() {
        val prop = tool.parameters.properties["filter"]
        assertNotNull(prop)
        assertTrue(prop!!.description.lowercase().contains("regex"))
    }
}

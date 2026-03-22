package com.workflow.orchestrator.agent.tools.runtime

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.runtime.WorkerType
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class GetRunConfigurationsToolTest {
    private val tool = GetRunConfigurationsTool()

    @Test
    fun `tool metadata is correct`() {
        assertEquals("get_run_configurations", tool.name)
        assertTrue(tool.parameters.properties.containsKey("type_filter"))
        assertTrue(tool.parameters.required.isEmpty())
        assertTrue(tool.description.contains("run/debug configurations"))
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
        assertEquals("get_run_configurations", def.function.name)
        assertTrue(def.function.description.isNotBlank())
        assertEquals("object", def.function.parameters.type)
        assertTrue(def.function.parameters.properties.containsKey("type_filter"))
        assertEquals("string", def.function.parameters.properties["type_filter"]?.type)
    }

    @Test
    fun `type_filter enum values are correct`() {
        val enumValues = tool.parameters.properties["type_filter"]?.enumValues
        assertNotNull(enumValues)
        assertEquals(
            listOf("application", "spring_boot", "junit", "gradle", "remote_debug"),
            enumValues
        )
    }

    @Test
    fun `type_filter is optional`() {
        assertTrue(tool.parameters.required.isEmpty())
    }

    @Test
    fun `execute handles missing RunManager gracefully`() = runTest {
        val project = mockk<Project>(relaxed = true)

        val result = tool.execute(buildJsonObject {}, project)

        // Without a running IDE, RunManager.getInstance() will throw — tool should handle gracefully
        assertTrue(result.isError)
        assertTrue(result.content.contains("Error"))
    }

    @Test
    fun `execute with type_filter handles missing RunManager gracefully`() = runTest {
        val project = mockk<Project>(relaxed = true)

        val result = tool.execute(buildJsonObject {
            put("type_filter", JsonPrimitive("junit"))
        }, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("Error"))
    }

    @Test
    fun `description mentions settings`() {
        assertTrue(tool.description.contains("settings"))
    }
}

package com.workflow.orchestrator.agent.tools.runtime

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.runtime.WorkerType
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class GetTestResultsToolTest {
    private val tool = GetTestResultsTool()

    @Test
    fun `tool metadata is correct`() {
        assertEquals("get_test_results", tool.name)
        assertTrue(tool.parameters.properties.containsKey("config_name"))
        assertTrue(tool.parameters.properties.containsKey("status_filter"))
        assertTrue(tool.parameters.required.isEmpty())
        assertTrue(tool.description.contains("test results"))
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
        assertEquals("get_test_results", def.function.name)
        assertTrue(def.function.description.isNotBlank())
        assertEquals("object", def.function.parameters.type)
        assertTrue(def.function.parameters.properties.containsKey("config_name"))
        assertTrue(def.function.parameters.properties.containsKey("status_filter"))
    }

    @Test
    fun `status_filter enum values are correct`() {
        val enumValues = tool.parameters.properties["status_filter"]?.enumValues
        assertNotNull(enumValues)
        assertEquals(
            listOf("FAILED", "ERROR", "PASSED", "SKIPPED"),
            enumValues
        )
    }

    @Test
    fun `config_name and status_filter are both optional`() {
        assertTrue(tool.parameters.required.isEmpty())
    }

    @Test
    fun `parameters have correct types`() {
        assertEquals("string", tool.parameters.properties["config_name"]?.type)
        assertEquals("string", tool.parameters.properties["status_filter"]?.type)
    }

    @Test
    fun `description mentions assertions and stack traces`() {
        assertTrue(tool.description.contains("pass/fail"))
        assertTrue(tool.description.contains("stack traces"))
    }

    @Test
    fun `execute handles missing RunContentManager gracefully`() = runTest {
        val project = mockk<Project>(relaxed = true)

        val result = tool.execute(buildJsonObject {}, project)

        // Without a running IDE, RunContentManager.getInstance() will throw — tool should handle gracefully
        assertTrue(result.isError)
    }

    @Test
    fun `execute with config_name handles missing RunContentManager gracefully`() = runTest {
        val project = mockk<Project>(relaxed = true)

        val result = tool.execute(buildJsonObject {
            put("config_name", JsonPrimitive("MyTests"))
        }, project)

        assertTrue(result.isError)
    }

    @Test
    fun `execute with status_filter handles missing RunContentManager gracefully`() = runTest {
        val project = mockk<Project>(relaxed = true)

        val result = tool.execute(buildJsonObject {
            put("status_filter", JsonPrimitive("FAILED"))
        }, project)

        assertTrue(result.isError)
    }
}

package com.workflow.orchestrator.agent.tools.psi

import com.workflow.orchestrator.agent.ide.LanguageProviderRegistry
import com.workflow.orchestrator.agent.tools.WorkerType
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ReadWriteAccessToolTest {

    private val registry = LanguageProviderRegistry()

    @Test
    fun `tool metadata is correct`() {
        val tool = ReadWriteAccessTool(registry)
        assertEquals("read_write_access", tool.name)
        assertTrue(tool.description.contains("read"))
        assertTrue(tool.description.contains("write"))
        assertTrue(tool.parameters.required.contains("file"))
        assertTrue(tool.parameters.properties.containsKey("file"))
        assertTrue(tool.parameters.properties.containsKey("offset"))
        assertTrue(tool.parameters.properties.containsKey("line"))
        assertTrue(tool.parameters.properties.containsKey("column"))
        assertTrue(tool.parameters.properties.containsKey("scope"))
    }

    @Test
    fun `allowedWorkers includes ANALYZER, CODER, and REVIEWER`() {
        val tool = ReadWriteAccessTool(registry)
        assertEquals(
            setOf(WorkerType.ANALYZER, WorkerType.CODER, WorkerType.REVIEWER),
            tool.allowedWorkers
        )
    }

    @Test
    fun `file is the only required parameter`() {
        val tool = ReadWriteAccessTool(registry)
        assertEquals(listOf("file"), tool.parameters.required)
    }

    @Test
    fun `offset, line, column, and scope are optional`() {
        val tool = ReadWriteAccessTool(registry)
        assertFalse(tool.parameters.required.contains("offset"))
        assertFalse(tool.parameters.required.contains("line"))
        assertFalse(tool.parameters.required.contains("column"))
        assertFalse(tool.parameters.required.contains("scope"))
    }

    @Test
    fun `toToolDefinition produces valid schema`() {
        val tool = ReadWriteAccessTool(registry)
        val def = tool.toToolDefinition()
        assertEquals("function", def.type)
        assertEquals("read_write_access", def.function.name)
        assertTrue(def.function.parameters.required.contains("file"))
        assertEquals(5, def.function.parameters.properties.size)
    }

    @Test
    fun `execute returns error when file parameter is missing`() = runTest {
        val tool = ReadWriteAccessTool(registry)
        val project = mockk<com.intellij.openapi.project.Project> {
            every { basePath } returns "/tmp"
        }
        val params = kotlinx.serialization.json.buildJsonObject { }

        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("'file' parameter is required"))
    }

    @Test
    fun `execute returns error when neither offset nor line is provided`() = runTest {
        val tool = ReadWriteAccessTool(registry)
        val project = mockk<com.intellij.openapi.project.Project> {
            every { basePath } returns "/tmp"
        }
        val params = kotlinx.serialization.json.buildJsonObject {
            put("file", kotlinx.serialization.json.JsonPrimitive("Foo.java"))
        }

        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("at least one of 'offset' or 'line'"))
    }

    @Test
    fun `execute returns dumbModeError when indexing`() = runTest {
        val tool = ReadWriteAccessTool(registry)
        val project = mockk<com.intellij.openapi.project.Project> {
            every { basePath } returns "/tmp"
        }
        val params = kotlinx.serialization.json.buildJsonObject {
            put("file", kotlinx.serialization.json.JsonPrimitive("Foo.java"))
            put("line", kotlinx.serialization.json.JsonPrimitive(10))
        }

        mockkStatic(com.intellij.openapi.project.DumbService::class)
        every { com.intellij.openapi.project.DumbService.isDumb(project) } returns true

        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("indexing"))
        unmockkStatic(com.intellij.openapi.project.DumbService::class)
    }

    @Test
    fun `description mentions data flow and mutations`() {
        val tool = ReadWriteAccessTool(registry)
        assertTrue(tool.description.contains("data flow"))
        assertTrue(tool.description.contains("mutations"))
    }

    @Test
    fun `scope parameter has correct description`() {
        val tool = ReadWriteAccessTool(registry)
        val scopeProp = tool.parameters.properties["scope"]
        assertNotNull(scopeProp)
        assertTrue(scopeProp!!.description.contains("project"))
        assertTrue(scopeProp.description.contains("file"))
    }
}

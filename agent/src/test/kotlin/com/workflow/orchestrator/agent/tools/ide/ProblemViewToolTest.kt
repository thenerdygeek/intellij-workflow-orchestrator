package com.workflow.orchestrator.agent.tools.ide

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.WorkerType
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ProblemViewToolTest {
    private val tool = ProblemViewTool()

    @Test
    fun `tool name is problem_view`() {
        assertEquals("problem_view", tool.name)
    }

    @Test
    fun `description mentions errors and warnings`() {
        assertTrue(tool.description.contains("errors"))
        assertTrue(tool.description.contains("warnings"))
    }

    @Test
    fun `description warns about editor-opened files`() {
        assertTrue(tool.description.contains("opened in the editor"))
    }

    @Test
    fun `parameters include file and severity`() {
        val props = tool.parameters.properties
        assertTrue("file" in props)
        assertTrue("severity" in props)
    }

    @Test
    fun `file parameter is optional`() {
        assertFalse("file" in tool.parameters.required)
    }

    @Test
    fun `severity parameter is optional`() {
        assertFalse("severity" in tool.parameters.required)
    }

    @Test
    fun `severity parameter has enum values`() {
        val severityProp = tool.parameters.properties["severity"]!!
        assertNotNull(severityProp.enumValues)
        assertEquals(listOf("error", "warning", "all"), severityProp.enumValues)
    }

    @Test
    fun `no required parameters`() {
        assertTrue(tool.parameters.required.isEmpty())
    }

    @Test
    fun `allowedWorkers includes ANALYZER, CODER, REVIEWER`() {
        assertEquals(
            setOf(WorkerType.ANALYZER, WorkerType.CODER, WorkerType.REVIEWER),
            tool.allowedWorkers
        )
    }

    @Test
    fun `toToolDefinition produces valid schema`() {
        val def = tool.toToolDefinition()
        assertEquals("function", def.type)
        assertEquals("problem_view", def.function.name)
        assertTrue(def.function.description.isNotBlank())
        assertEquals("object", def.function.parameters.type)
        assertEquals(2, def.function.parameters.properties.size)
        assertTrue(def.function.parameters.required.isEmpty())
    }

    @Test
    fun `execute with invalid severity returns error`() = runTest {
        val project = mockk<Project>(relaxed = true)
        every { project.basePath } returns "/tmp/test-project"

        val result = tool.execute(buildJsonObject {
            put("severity", "critical")
        }, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("severity"))
    }

    @Test
    fun `execute with file param handles missing project base path`() = runTest {
        val project = mockk<Project>(relaxed = true)
        every { project.basePath } returns null

        val result = tool.execute(buildJsonObject {
            put("file", "src/Main.kt")
        }, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("project base path"))
    }

    @Test
    fun `execute with nonexistent file returns error`() = runTest {
        val project = mockk<Project>(relaxed = true)
        every { project.basePath } returns "/tmp/nonexistent-project-dir-12345"

        val result = tool.execute(buildJsonObject {
            put("file", "nonexistent/File.kt")
        }, project)

        assertTrue(result.isError)
    }

    @Test
    fun `execute without file param handles missing FileEditorManager gracefully`() = runTest {
        val project = mockk<Project>(relaxed = true)
        every { project.basePath } returns "/tmp/test-project"

        val result = tool.execute(buildJsonObject {}, project)

        // Without a running IDE, FileEditorManager.getInstance() will throw
        assertTrue(result.isError)
        assertTrue(result.content.contains("Error"))
    }

    @Test
    fun `execute with path traversal returns error`() = runTest {
        val project = mockk<Project>(relaxed = true)
        every { project.basePath } returns "/tmp/test-project"

        val result = tool.execute(buildJsonObject {
            put("file", "../../etc/passwd")
        }, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("outside the project"))
    }

    @Test
    fun `file parameter description mentions listing all files`() {
        val desc = tool.parameters.properties["file"]!!.description
        assertTrue(desc.contains("Lists all problem files if omitted"))
    }

    @Test
    fun `severity parameter default is documented in description`() {
        val desc = tool.parameters.properties["severity"]!!.description
        assertTrue(desc.contains("all"))
    }
}

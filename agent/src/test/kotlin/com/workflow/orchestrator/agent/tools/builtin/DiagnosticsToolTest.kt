package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.runtime.WorkerType
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DiagnosticsToolTest {

    private val project = mockk<Project> { every { basePath } returns System.getProperty("java.io.tmpdir") }

    @Test
    fun `tool name is diagnostics`() {
        val tool = DiagnosticsTool()
        assertEquals("diagnostics", tool.name)
    }

    @Test
    fun `allowedWorkers includes CODER and REVIEWER`() {
        val tool = DiagnosticsTool()
        assertTrue(tool.allowedWorkers.contains(WorkerType.CODER))
        assertTrue(tool.allowedWorkers.contains(WorkerType.REVIEWER))
    }

    @Test
    fun `execute handles file not found`() = runTest {
        val tool = DiagnosticsTool()
        val params = buildJsonObject { put("path", "nonexistent/file.kt") }

        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("not found") || result.content.contains("outside"))
    }

    @Test
    fun `execute returns no-diagnostics for non-java-kotlin files`() = runTest {
        val tool = DiagnosticsTool()
        val tmpFile = java.io.File.createTempFile("test", ".txt").apply { writeText("hello"); deleteOnExit() }
        val params = buildJsonObject { put("path", tmpFile.absolutePath) }

        val result = tool.execute(params, project)

        assertFalse(result.isError)
        assertTrue(result.content.contains("No diagnostics available"))
    }

    @Test
    fun `tool definition schema is valid`() {
        val tool = DiagnosticsTool()
        val def = tool.toToolDefinition()

        assertEquals("function", def.type)
        assertEquals("diagnostics", def.function.name)
        assertTrue(def.function.description.isNotBlank())
        assertTrue(def.function.parameters.properties.containsKey("path"))
        assertTrue(def.function.parameters.required.contains("path"))
    }

    @Test
    fun `parameters require path`() {
        val tool = DiagnosticsTool()
        assertEquals(listOf("path"), tool.parameters.required)
        assertTrue(tool.parameters.properties.containsKey("path"))
        assertEquals("string", tool.parameters.properties["path"]?.type)
    }

    @Test
    fun `description is non-blank`() {
        val tool = DiagnosticsTool()
        assertTrue(tool.description.isNotBlank())
    }
}

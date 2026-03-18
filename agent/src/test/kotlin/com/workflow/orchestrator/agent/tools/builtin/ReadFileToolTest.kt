package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class ReadFileToolTest {

    @TempDir
    lateinit var tempDir: Path

    private val project = mockk<Project> { every { basePath } returns "/tmp" }

    @Test
    fun `execute reads file content with line numbers`() = runTest {
        val tmpFile = File(tempDir.toFile(), "test.kt").apply {
            writeText("line1\nline2\nline3\nline4\nline5")
        }
        val tool = ReadFileTool()
        val params = buildJsonObject {
            put("path", tmpFile.absolutePath)
        }

        val result = tool.execute(params, project)

        assertFalse(result.isError)
        assertTrue(result.content.contains("1\tline1"))
        assertTrue(result.content.contains("5\tline5"))
        assertTrue(result.summary.contains("5 lines"))
    }

    @Test
    fun `execute with offset and limit`() = runTest {
        val tmpFile = File(tempDir.toFile(), "big.kt").apply {
            writeText((1..100).joinToString("\n") { "line $it" })
        }
        val tool = ReadFileTool()
        val params = buildJsonObject {
            put("path", tmpFile.absolutePath)
            put("offset", 10)
            put("limit", 5)
        }

        val result = tool.execute(params, project)

        assertFalse(result.isError)
        assertTrue(result.content.contains("10\tline 10"))
        assertTrue(result.content.contains("14\tline 14"))
        assertTrue(result.summary.contains("5 lines"))
        assertTrue(result.content.contains("more lines"))
    }

    @Test
    fun `execute returns error for missing file`() = runTest {
        val tool = ReadFileTool()
        val params = buildJsonObject { put("path", "/nonexistent/file.kt") }
        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("not found"))
    }

    @Test
    fun `execute returns error when path is missing`() = runTest {
        val tool = ReadFileTool()
        val params = buildJsonObject { }
        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("'path' parameter required"))
    }

    @Test
    fun `execute resolves relative path against project basePath`() = runTest {
        val projectDir = tempDir.toFile()
        val subFile = File(projectDir, "src/Main.kt").apply {
            parentFile.mkdirs()
            writeText("fun main() {}")
        }
        val proj = mockk<Project> { every { basePath } returns projectDir.absolutePath }
        val tool = ReadFileTool()
        val params = buildJsonObject { put("path", "src/Main.kt") }

        val result = tool.execute(params, proj)

        assertFalse(result.isError)
        assertTrue(result.content.contains("fun main()"))
    }

    @Test
    fun `execute reads empty file`() = runTest {
        val tmpFile = File(tempDir.toFile(), "empty.kt").apply {
            writeText("")
        }
        val tool = ReadFileTool()
        val params = buildJsonObject { put("path", tmpFile.absolutePath) }

        val result = tool.execute(params, project)

        assertFalse(result.isError)
        assertTrue(result.summary.contains("1 lines")) // empty file has 1 empty line
    }

    @Test
    fun `tool metadata is correct`() {
        val tool = ReadFileTool()
        assertEquals("read_file", tool.name)
        assertTrue(tool.parameters.required.contains("path"))
        assertTrue(tool.parameters.properties.containsKey("offset"))
        assertTrue(tool.parameters.properties.containsKey("limit"))
    }

    @Test
    fun `toToolDefinition produces valid schema`() {
        val tool = ReadFileTool()
        val def = tool.toToolDefinition()
        assertEquals("function", def.type)
        assertEquals("read_file", def.function.name)
        assertTrue(def.function.parameters.properties.isNotEmpty())
    }
}

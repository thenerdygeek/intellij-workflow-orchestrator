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

class EditFileToolTest {

    @TempDir
    lateinit var tempDir: Path

    private val project = mockk<Project> { every { basePath } returns "/tmp" }

    @Test
    fun `execute replaces unique string in file`() = runTest {
        val tmpFile = File(tempDir.toFile(), "test.kt").apply {
            writeText("fun hello() {\n    println(\"Hello\")\n}")
        }
        val tool = EditFileTool()
        val params = buildJsonObject {
            put("path", tmpFile.absolutePath)
            put("old_string", "println(\"Hello\")")
            put("new_string", "println(\"World\")")
        }

        val result = tool.execute(params, project)

        assertFalse(result.isError)
        val newContent = tmpFile.readText()
        assertTrue(newContent.contains("println(\"World\")"))
        assertFalse(newContent.contains("println(\"Hello\")"))
        assertTrue(result.artifacts.contains(tmpFile.absolutePath))
    }

    @Test
    fun `execute returns error when old_string not found`() = runTest {
        val tmpFile = File(tempDir.toFile(), "test.kt").apply {
            writeText("fun hello() {}")
        }
        val tool = EditFileTool()
        val params = buildJsonObject {
            put("path", tmpFile.absolutePath)
            put("old_string", "nonexistent text")
            put("new_string", "replacement")
        }

        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("not found"))
    }

    @Test
    fun `execute returns error when old_string occurs multiple times`() = runTest {
        val tmpFile = File(tempDir.toFile(), "test.kt").apply {
            writeText("val a = 1\nval b = 1\nval c = 1")
        }
        val tool = EditFileTool()
        val params = buildJsonObject {
            put("path", tmpFile.absolutePath)
            put("old_string", "val")
            put("new_string", "var")
        }

        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("3 times"))
        // File should not be modified
        assertEquals("val a = 1\nval b = 1\nval c = 1", tmpFile.readText())
    }

    @Test
    fun `execute returns error for missing file`() = runTest {
        val tool = EditFileTool()
        val params = buildJsonObject {
            put("path", "/nonexistent/file.kt")
            put("old_string", "a")
            put("new_string", "b")
        }

        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("not found"))
    }

    @Test
    fun `execute returns error when path is missing`() = runTest {
        val tool = EditFileTool()
        val params = buildJsonObject {
            put("old_string", "a")
            put("new_string", "b")
        }

        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("'path' parameter required"))
    }

    @Test
    fun `execute returns error when old_string is missing`() = runTest {
        val tool = EditFileTool()
        val params = buildJsonObject {
            put("path", "/some/file.kt")
            put("new_string", "b")
        }

        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("'old_string' parameter required"))
    }

    @Test
    fun `execute returns error when new_string is missing`() = runTest {
        val tool = EditFileTool()
        val params = buildJsonObject {
            put("path", "/some/file.kt")
            put("old_string", "a")
        }

        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("'new_string' parameter required"))
    }

    @Test
    fun `execute resolves relative path against project basePath`() = runTest {
        val projectDir = tempDir.toFile()
        val subFile = File(projectDir, "src/Main.kt").apply {
            parentFile.mkdirs()
            writeText("fun main() { old }")
        }
        val proj = mockk<Project> { every { basePath } returns projectDir.absolutePath }
        val tool = EditFileTool()
        val params = buildJsonObject {
            put("path", "src/Main.kt")
            put("old_string", "old")
            put("new_string", "new")
        }

        val result = tool.execute(params, proj)

        assertFalse(result.isError)
        assertTrue(subFile.readText().contains("new"))
    }

    @Test
    fun `tool metadata is correct`() {
        val tool = EditFileTool()
        assertEquals("edit_file", tool.name)
        assertEquals(setOf(com.workflow.orchestrator.agent.runtime.WorkerType.CODER), tool.allowedWorkers)
        assertTrue(tool.parameters.required.containsAll(listOf("path", "old_string", "new_string")))
    }
}

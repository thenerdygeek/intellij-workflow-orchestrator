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

    private val project by lazy { mockk<Project> { every { basePath } returns tempDir.toFile().absolutePath } }

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
        val params = buildJsonObject { put("path", "nonexistent/file.kt") }
        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("not found") || result.content.contains("outside"))
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

    @Test
    fun `execute returns error for binary file`() = runTest {
        val binaryFile = File(tempDir.toFile(), "archive.jar").apply {
            writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04)) // ZIP magic bytes
        }
        val tool = ReadFileTool()
        val params = buildJsonObject { put("path", binaryFile.absolutePath) }

        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("binary file"))
        assertTrue(result.content.contains("archive.jar"))
    }

    @Test
    fun `execute returns error for various binary extensions`() = runTest {
        val extensions = listOf("class", "png", "jpg", "zip", "exe", "pdf", "dll", "gif", "svg", "woff2")
        val tool = ReadFileTool()

        for (ext in extensions) {
            val binaryFile = File(tempDir.toFile(), "file.$ext").apply {
                writeBytes(byteArrayOf(0x00, 0x01))
            }
            val params = buildJsonObject { put("path", binaryFile.absolutePath) }
            val result = tool.execute(params, project)

            assertTrue(result.isError, "Expected error for .$ext file")
            assertTrue(result.content.contains("binary file"), "Expected 'binary file' message for .$ext")
        }
    }

    @Test
    fun `execute returns error for file exceeding size limit`() = runTest {
        val largeFile = File(tempDir.toFile(), "huge.kt").apply {
            // Create a file just over 10MB
            val chunk = ByteArray(1_000_000) { 'x'.code.toByte() }
            outputStream().use { out ->
                repeat(11) { out.write(chunk) }
            }
        }
        val tool = ReadFileTool()
        val params = buildJsonObject { put("path", largeFile.absolutePath) }

        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("too large"))
        assertTrue(result.content.contains("huge.kt"))
    }

    @Test
    fun `execute truncates long lines at 2000 chars`() = runTest {
        val longLine = "x".repeat(3000)
        val tmpFile = File(tempDir.toFile(), "long-lines.kt").apply {
            writeText("short line\n$longLine\nanother short line")
        }
        val tool = ReadFileTool()
        val params = buildJsonObject { put("path", tmpFile.absolutePath) }

        val result = tool.execute(params, project)

        assertFalse(result.isError)
        // Line 1 should be untouched
        assertTrue(result.content.contains("1\tshort line"))
        // Line 2 should be truncated
        assertTrue(result.content.contains("[line truncated at 2000 chars]"))
        // The truncated line should not contain the full 3000 chars
        val line2 = result.content.lines().find { it.startsWith("2\t") }!!
        // 2\t + 2000 x's + truncation marker
        assertTrue(line2.length < 3000 + 50)
        // Line 3 should be untouched
        assertTrue(result.content.contains("3\tanother short line"))
    }

    @Test
    fun `execute does not truncate line at exactly 2000 chars`() = runTest {
        val exactLine = "y".repeat(2000)
        val tmpFile = File(tempDir.toFile(), "exact.kt").apply {
            writeText(exactLine)
        }
        val tool = ReadFileTool()
        val params = buildJsonObject { put("path", tmpFile.absolutePath) }

        val result = tool.execute(params, project)

        assertFalse(result.isError)
        assertFalse(result.content.contains("[line truncated"))
        assertTrue(result.content.contains(exactLine))
    }

    @Test
    fun `execute allows non-binary file under size limit`() = runTest {
        val normalFile = File(tempDir.toFile(), "normal.kt").apply {
            writeText("fun hello() = println(\"world\")")
        }
        val tool = ReadFileTool()
        val params = buildJsonObject { put("path", normalFile.absolutePath) }

        val result = tool.execute(params, project)

        assertFalse(result.isError)
        assertTrue(result.content.contains("fun hello()"))
    }
}

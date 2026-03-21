package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class GlobFilesToolTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var project: Project

    @BeforeEach
    fun setUp() {
        val srcDir = File(tempDir.toFile(), "src").apply { mkdirs() }
        File(srcDir, "Main.kt").writeText("fun main() {}")
        File(srcDir, "Test.kt").writeText("class Test {}")
        File(srcDir, "Readme.md").writeText("# Readme")

        val libDir = File(tempDir.toFile(), "lib").apply { mkdirs() }
        File(libDir, "Utils.kt").writeText("object Utils {}")

        project = mockk<Project> { every { basePath } returns tempDir.toFile().absolutePath }
    }

    @Test
    fun `finds kotlin files with glob pattern`() = runTest {
        val tool = GlobFilesTool()
        val params = buildJsonObject {
            put("pattern", "**/*.kt")
        }

        val result = tool.execute(params, project)

        assertFalse(result.isError)
        assertTrue(result.content.contains("Main.kt"))
        assertTrue(result.content.contains("Test.kt"))
        assertTrue(result.content.contains("Utils.kt"))
        assertFalse(result.content.contains("Readme.md"))
    }

    @Test
    fun `returns files sorted by modification time`() = runTest {
        // Create files with distinct modification times
        val dir = File(tempDir.toFile(), "sorted").apply { mkdirs() }
        val oldest = File(dir, "oldest.kt").apply { writeText("1"); setLastModified(1000L) }
        val middle = File(dir, "middle.kt").apply { writeText("2"); setLastModified(2000L) }
        val newest = File(dir, "newest.kt").apply { writeText("3"); setLastModified(3000L) }

        val tool = GlobFilesTool()
        val params = buildJsonObject {
            put("pattern", "**/*.kt")
            put("path", "sorted")
        }

        val result = tool.execute(params, project)

        assertFalse(result.isError)
        val lines = result.content.lines().filter { it.isNotBlank() }
        assertEquals(3, lines.size)
        // Newest first
        assertTrue(lines[0].contains("newest.kt"), "First should be newest, got: ${lines[0]}")
        assertTrue(lines[1].contains("middle.kt"), "Second should be middle, got: ${lines[1]}")
        assertTrue(lines[2].contains("oldest.kt"), "Third should be oldest, got: ${lines[2]}")
    }

    @Test
    fun `respects max_results limit`() = runTest {
        // Create many files
        val dir = File(tempDir.toFile(), "many").apply { mkdirs() }
        for (i in 1..10) {
            File(dir, "File$i.kt").writeText("class File$i")
        }

        val tool = GlobFilesTool()
        val params = buildJsonObject {
            put("pattern", "**/*.kt")
            put("path", "many")
            put("max_results", 3)
        }

        val result = tool.execute(params, project)

        assertFalse(result.isError)
        val lines = result.content.lines().filter { it.isNotBlank() && !it.startsWith("...") }
        assertEquals(3, lines.size)
        assertTrue(result.content.contains("limited to 3"))
    }

    @Test
    fun `returns error when pattern is missing`() = runTest {
        val tool = GlobFilesTool()
        val params = buildJsonObject { }

        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("'pattern' parameter required"))
    }

    @Test
    fun `skips excluded directories`() = runTest {
        val gitDir = File(tempDir.toFile(), ".git").apply { mkdirs() }
        File(gitDir, "config.kt").writeText("git config")
        val nodeDir = File(tempDir.toFile(), "node_modules").apply { mkdirs() }
        File(nodeDir, "index.kt").writeText("node file")

        val tool = GlobFilesTool()
        val params = buildJsonObject {
            put("pattern", "**/*.kt")
        }

        val result = tool.execute(params, project)

        assertFalse(result.isError)
        assertFalse(result.content.contains(".git/"), "Should skip .git directory")
        assertFalse(result.content.contains("node_modules/"), "Should skip node_modules directory")
        // Should still find src/ and lib/ files
        assertTrue(result.content.contains("Main.kt"))
    }

    @Test
    fun `handles invalid glob pattern`() = runTest {
        val tool = GlobFilesTool()
        val params = buildJsonObject {
            put("pattern", "[invalid")
        }

        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("invalid glob pattern"))
    }

    @Test
    fun `returns no matches message when nothing found`() = runTest {
        val tool = GlobFilesTool()
        val params = buildJsonObject {
            put("pattern", "**/*.xyz")
        }

        val result = tool.execute(params, project)

        assertFalse(result.isError)
        assertTrue(result.content.contains("No files found matching"))
    }

    @Test
    fun `respects path parameter to limit search scope`() = runTest {
        val tool = GlobFilesTool()
        val params = buildJsonObject {
            put("pattern", "**/*.kt")
            put("path", "src")
        }

        val result = tool.execute(params, project)

        assertFalse(result.isError)
        assertTrue(result.content.contains("Main.kt"))
        assertTrue(result.content.contains("Test.kt"))
        assertFalse(result.content.contains("Utils.kt"), "Should not find files outside path scope")
    }

    @Test
    fun `returns error for nonexistent directory`() = runTest {
        val tool = GlobFilesTool()
        val params = buildJsonObject {
            put("pattern", "**/*.kt")
            put("path", "nonexistent_dir")
        }

        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("not found") || result.content.contains("not available"))
    }

    @Test
    fun `matches filename-only patterns`() = runTest {
        val tool = GlobFilesTool()
        val params = buildJsonObject {
            put("pattern", "*.md")
        }

        val result = tool.execute(params, project)

        assertFalse(result.isError)
        assertTrue(result.content.contains("Readme.md"))
    }

    @Test
    fun `uses forward slashes in output`() = runTest {
        val tool = GlobFilesTool()
        val params = buildJsonObject {
            put("pattern", "**/*.kt")
        }

        val result = tool.execute(params, project)

        assertFalse(result.isError)
        assertFalse(result.content.contains("\\"), "Output should use forward slashes")
    }

    @Test
    fun `tool metadata is correct`() {
        val tool = GlobFilesTool()
        assertEquals("glob_files", tool.name)
        assertTrue(tool.parameters.required.contains("pattern"))
        assertTrue(tool.parameters.properties.containsKey("pattern"))
        assertTrue(tool.parameters.properties.containsKey("path"))
        assertTrue(tool.parameters.properties.containsKey("max_results"))
    }
}

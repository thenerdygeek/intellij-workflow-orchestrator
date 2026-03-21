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

class SearchCodeToolTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var project: Project

    @BeforeEach
    fun setUp() {
        // Create a small project structure
        val srcDir = File(tempDir.toFile(), "src").apply { mkdirs() }
        File(srcDir, "Main.kt").writeText("fun main() {\n    println(\"Hello world\")\n}")
        File(srcDir, "Service.kt").writeText("class UserService {\n    fun findUser(id: String): User? = null\n    fun deleteUser(id: String) {}\n}")
        File(srcDir, "Model.kt").writeText("data class User(val id: String, val name: String)")
        File(srcDir, "Config.java").writeText("public class Config {\n    public String getUser() { return null; }\n}")

        val testDir = File(tempDir.toFile(), "test").apply { mkdirs() }
        File(testDir, "MainTest.kt").writeText("fun testMain() {\n    println(\"test\")\n}")

        project = mockk<Project> { every { basePath } returns tempDir.toFile().absolutePath }
    }

    @Test
    fun `execute finds matches across multiple files`() = runTest {
        val tool = SearchCodeTool()
        val params = buildJsonObject {
            put("pattern", "fun ")
        }

        val result = tool.execute(params, project)

        assertFalse(result.isError)
        assertTrue(result.content.contains("Main.kt"))
        assertTrue(result.content.contains("Service.kt"))
        assertTrue(result.content.contains("MainTest.kt"))
    }

    @Test
    fun `execute respects path parameter`() = runTest {
        val tool = SearchCodeTool()
        val params = buildJsonObject {
            put("pattern", "fun ")
            put("path", "src")
        }

        val result = tool.execute(params, project)

        assertFalse(result.isError)
        assertTrue(result.content.contains("Main.kt"))
        assertFalse(result.content.contains("MainTest.kt"))
    }

    @Test
    fun `execute respects scope parameter as alias for path`() = runTest {
        val tool = SearchCodeTool()
        val params = buildJsonObject {
            put("pattern", "fun ")
            put("scope", "src")
        }

        val result = tool.execute(params, project)

        assertFalse(result.isError)
        assertTrue(result.content.contains("Main.kt"))
        assertFalse(result.content.contains("MainTest.kt"))
    }

    @Test
    fun `backward compat query still works as alias for pattern`() = runTest {
        val tool = SearchCodeTool()
        val params = buildJsonObject {
            put("query", "fun main")
        }

        val result = tool.execute(params, project)

        assertFalse(result.isError)
        assertTrue(result.content.contains("Main.kt"))
    }

    @Test
    fun `execute returns no matches message`() = runTest {
        val tool = SearchCodeTool()
        val params = buildJsonObject {
            put("pattern", "nonexistentXYZ123")
        }

        val result = tool.execute(params, project)

        assertFalse(result.isError)
        assertTrue(result.content.contains("No matches found"))
    }

    @Test
    fun `execute respects max_results`() = runTest {
        val tool = SearchCodeTool()
        val params = buildJsonObject {
            put("pattern", "fun|class|data|val")
            put("output_mode", "content")
            put("max_results", 2)
        }

        val result = tool.execute(params, project)

        assertFalse(result.isError)
        // Count match lines (lines with :N: format that are actual matches)
        val matchLines = result.content.lines().filter { it.contains(":") && !it.startsWith("...") }
        assertTrue(matchLines.size <= 2)
        assertTrue(result.content.contains("results limited"))
    }

    @Test
    fun `execute returns error when pattern is missing`() = runTest {
        val tool = SearchCodeTool()
        val params = buildJsonObject { }

        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("'pattern' parameter required"))
    }

    @Test
    fun `execute skips binary files`() = runTest {
        File(tempDir.toFile(), "image.png").writeText("fun shouldNotMatch()")
        val tool = SearchCodeTool()
        val params = buildJsonObject {
            put("pattern", "shouldNotMatch")
        }

        val result = tool.execute(params, project)

        assertTrue(result.content.contains("No matches found"))
    }

    @Test
    fun `execute skips git directory`() = runTest {
        val gitDir = File(tempDir.toFile(), ".git").apply { mkdirs() }
        File(gitDir, "config").writeText("fun shouldNotMatch()")
        val tool = SearchCodeTool()
        val params = buildJsonObject {
            put("pattern", "shouldNotMatch")
        }

        val result = tool.execute(params, project)

        assertTrue(result.content.contains("No matches found"))
    }

    @Test
    fun `execute skips workflow directory`() = runTest {
        val workflowDir = File(tempDir.toFile(), ".workflow").apply { mkdirs() }
        File(workflowDir, "agent-memory.md").writeText("fun shouldNotMatch()")
        val tool = SearchCodeTool()
        val params = buildJsonObject {
            put("pattern", "shouldNotMatch")
        }

        val result = tool.execute(params, project)

        assertTrue(result.content.contains("No matches found"))
    }

    @Test
    fun `execute handles regex patterns`() = runTest {
        val tool = SearchCodeTool()
        val params = buildJsonObject {
            put("pattern", "fun \\w+User")
        }

        val result = tool.execute(params, project)

        assertFalse(result.isError)
        assertTrue(result.content.contains("Service.kt"))
    }

    @Test
    fun `execute handles invalid regex by falling back to literal`() = runTest {
        val tool = SearchCodeTool()
        val params = buildJsonObject {
            put("pattern", "[invalid")
        }

        val result = tool.execute(params, project)

        // Should not throw, should return no matches (literal "[invalid" doesn't exist)
        assertFalse(result.isError)
    }

    @Test
    fun `execute returns error for nonexistent path`() = runTest {
        val tool = SearchCodeTool()
        val params = buildJsonObject {
            put("pattern", "fun")
            put("path", "nonexistent_dir")
        }

        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("not found"))
    }

    // --- Output mode: files (default) ---

    @Test
    fun `files mode returns only file paths`() = runTest {
        val tool = SearchCodeTool()
        val params = buildJsonObject {
            put("pattern", "fun ")
            put("output_mode", "files")
        }

        val result = tool.execute(params, project)

        assertFalse(result.isError)
        // Files mode should not contain line numbers (no :N: pattern with content)
        val lines = result.content.lines().filter { it.isNotBlank() }
        for (line in lines) {
            // Each line should be just a file path, no colon-delimited line numbers
            assertFalse(line.matches(Regex(".*:\\d+:.*")), "Files mode should not include line numbers: $line")
        }
        assertTrue(result.summary.contains("files match"))
    }

    @Test
    fun `files mode deduplicates paths`() = runTest {
        val tool = SearchCodeTool()
        val params = buildJsonObject {
            put("pattern", "fun ")
            put("output_mode", "files")
            put("path", "src")
        }

        val result = tool.execute(params, project)

        assertFalse(result.isError)
        val lines = result.content.lines().filter { it.isNotBlank() }
        assertEquals(lines.size, lines.distinct().size, "File paths should be unique")
    }

    @Test
    fun `default output mode is files`() = runTest {
        val tool = SearchCodeTool()
        val paramsWithMode = buildJsonObject {
            put("pattern", "User")
            put("output_mode", "files")
        }
        val paramsDefault = buildJsonObject {
            put("pattern", "User")
        }

        val resultWithMode = tool.execute(paramsWithMode, project)
        val resultDefault = tool.execute(paramsDefault, project)

        assertEquals(resultWithMode.content, resultDefault.content)
    }

    // --- Output mode: content ---

    @Test
    fun `content mode returns lines with line numbers`() = runTest {
        val tool = SearchCodeTool()
        val params = buildJsonObject {
            put("pattern", "fun main")
            put("output_mode", "content")
        }

        val result = tool.execute(params, project)

        assertFalse(result.isError)
        assertTrue(result.content.contains("Main.kt:1:"), "Should contain file:linenum format")
        assertTrue(result.content.contains("fun main"))
        assertTrue(result.summary.contains("Found"))
    }

    @Test
    fun `content mode with context lines includes surrounding lines`() = runTest {
        val tool = SearchCodeTool()
        val params = buildJsonObject {
            put("pattern", "findUser")
            put("output_mode", "content")
            put("context_lines", 1)
        }

        val result = tool.execute(params, project)

        assertFalse(result.isError)
        // Should contain the match line with > prefix
        assertTrue(result.content.contains(">"), "Match line should have > prefix with context")
        // Should contain context lines
        assertTrue(result.content.contains("---") || result.content.lines().size > 1)
        // The line before findUser is "class UserService {"
        assertTrue(result.content.contains("UserService"), "Should include context before match")
        // The line after findUser is "fun deleteUser..."
        assertTrue(result.content.contains("deleteUser"), "Should include context after match")
    }

    @Test
    fun `content mode with context_lines 2 includes more context`() = runTest {
        // Service.kt has 3 lines, match at line 2. Context 2 should get line 1 before and line 3 after
        val tool = SearchCodeTool()
        val params = buildJsonObject {
            put("pattern", "findUser")
            put("output_mode", "content")
            put("context_lines", 2)
        }

        val result = tool.execute(params, project)

        assertFalse(result.isError)
        assertTrue(result.content.contains("UserService"), "Should include context")
        assertTrue(result.content.contains("deleteUser"), "Should include context after")
    }

    // --- Output mode: count ---

    @Test
    fun `count mode returns match counts per file`() = runTest {
        val tool = SearchCodeTool()
        val params = buildJsonObject {
            put("pattern", "fun ")
            put("output_mode", "count")
            put("path", "src")
        }

        val result = tool.execute(params, project)

        assertFalse(result.isError)
        // Service.kt has 2 fun declarations (findUser, deleteUser)
        assertTrue(result.content.contains("Service.kt: 2 matches"), "Should show count for Service.kt")
        // Main.kt has 1 fun declaration
        assertTrue(result.content.contains("Main.kt: 1 matches"), "Should show count for Main.kt")
        assertTrue(result.summary.contains("matches across"))
        assertTrue(result.summary.contains("files"))
    }

    // --- File type filter ---

    @Test
    fun `file_type filters to only matching extension`() = runTest {
        val tool = SearchCodeTool()
        val params = buildJsonObject {
            put("pattern", "User")
            put("file_type", "kt")
            put("output_mode", "files")
        }

        val result = tool.execute(params, project)

        assertFalse(result.isError)
        // Config.java contains "getUser" but should be excluded
        assertFalse(result.content.contains("Config.java"), "Java file should be excluded by kt filter")
        assertTrue(result.content.contains("Service.kt") || result.content.contains("Model.kt"))
    }

    @Test
    fun `file_type java only returns java files`() = runTest {
        val tool = SearchCodeTool()
        val params = buildJsonObject {
            put("pattern", "User")
            put("file_type", "java")
            put("output_mode", "files")
        }

        val result = tool.execute(params, project)

        assertFalse(result.isError)
        assertTrue(result.content.contains("Config.java"))
        assertFalse(result.content.contains(".kt"))
    }

    // --- Case insensitive ---

    @Test
    fun `case_insensitive true matches regardless of case`() = runTest {
        val tool = SearchCodeTool()
        val params = buildJsonObject {
            put("pattern", "userservice")
            put("case_insensitive", true)
            put("output_mode", "content")
        }

        val result = tool.execute(params, project)

        assertFalse(result.isError)
        assertTrue(result.content.contains("Service.kt"), "Should find UserService with case-insensitive search")
        assertTrue(result.content.contains("UserService"))
    }

    @Test
    fun `case_insensitive false is default and case sensitive`() = runTest {
        val tool = SearchCodeTool()
        val params = buildJsonObject {
            put("pattern", "userservice")
            put("output_mode", "content")
        }

        val result = tool.execute(params, project)

        // "userservice" (all lowercase) should NOT match "UserService"
        assertTrue(result.content.contains("No matches found"))
    }

    // --- Tool metadata ---

    @Test
    fun `tool metadata is correct`() {
        val tool = SearchCodeTool()
        assertEquals("search_code", tool.name)
        assertTrue(tool.parameters.required.contains("pattern"))
        assertTrue(tool.parameters.properties.containsKey("pattern"))
        assertTrue(tool.parameters.properties.containsKey("output_mode"))
        assertTrue(tool.parameters.properties.containsKey("file_type"))
        assertTrue(tool.parameters.properties.containsKey("case_insensitive"))
        assertTrue(tool.parameters.properties.containsKey("context_lines"))
    }
}

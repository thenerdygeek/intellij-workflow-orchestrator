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

        val testDir = File(tempDir.toFile(), "test").apply { mkdirs() }
        File(testDir, "MainTest.kt").writeText("fun testMain() {\n    println(\"test\")\n}")

        project = mockk<Project> { every { basePath } returns tempDir.toFile().absolutePath }
    }

    @Test
    fun `execute finds matches across multiple files`() = runTest {
        val tool = SearchCodeTool()
        val params = buildJsonObject {
            put("query", "fun ")
        }

        val result = tool.execute(params, project)

        assertFalse(result.isError)
        assertTrue(result.content.contains("Main.kt"))
        assertTrue(result.content.contains("Service.kt"))
        assertTrue(result.content.contains("MainTest.kt"))
    }

    @Test
    fun `execute respects scope parameter`() = runTest {
        val tool = SearchCodeTool()
        val params = buildJsonObject {
            put("query", "fun ")
            put("scope", "src")
        }

        val result = tool.execute(params, project)

        assertFalse(result.isError)
        assertTrue(result.content.contains("Main.kt"))
        assertFalse(result.content.contains("MainTest.kt"))
    }

    @Test
    fun `execute returns no matches message`() = runTest {
        val tool = SearchCodeTool()
        val params = buildJsonObject {
            put("query", "nonexistentXYZ123")
        }

        val result = tool.execute(params, project)

        assertFalse(result.isError)
        assertTrue(result.content.contains("No matches found"))
    }

    @Test
    fun `execute respects max_results`() = runTest {
        val tool = SearchCodeTool()
        val params = buildJsonObject {
            put("query", "fun|class|data|val")
            put("max_results", 2)
        }

        val result = tool.execute(params, project)

        assertFalse(result.isError)
        // Count the number of match lines (exclude the truncation note)
        val matchLines = result.content.lines().filter { it.contains(":") && !it.startsWith("...") }
        assertTrue(matchLines.size <= 2)
        assertTrue(result.content.contains("results limited"))
    }

    @Test
    fun `execute returns error when query is missing`() = runTest {
        val tool = SearchCodeTool()
        val params = buildJsonObject { }

        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("'query' parameter required"))
    }

    @Test
    fun `execute skips binary files`() = runTest {
        File(tempDir.toFile(), "image.png").writeText("fun shouldNotMatch()")
        val tool = SearchCodeTool()
        val params = buildJsonObject {
            put("query", "shouldNotMatch")
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
            put("query", "shouldNotMatch")
        }

        val result = tool.execute(params, project)

        assertTrue(result.content.contains("No matches found"))
    }

    @Test
    fun `execute handles regex patterns`() = runTest {
        val tool = SearchCodeTool()
        val params = buildJsonObject {
            put("query", "fun \\w+User")
        }

        val result = tool.execute(params, project)

        assertFalse(result.isError)
        assertTrue(result.content.contains("Service.kt"))
        assertTrue(result.content.contains("findUser") || result.content.contains("deleteUser"))
    }

    @Test
    fun `execute handles invalid regex by falling back to literal`() = runTest {
        val tool = SearchCodeTool()
        val params = buildJsonObject {
            put("query", "[invalid")
        }

        val result = tool.execute(params, project)

        // Should not throw, should return no matches (literal "[invalid" doesn't exist)
        assertFalse(result.isError)
    }

    @Test
    fun `execute returns error for nonexistent scope`() = runTest {
        val tool = SearchCodeTool()
        val params = buildJsonObject {
            put("query", "fun")
            put("scope", "nonexistent_dir")
        }

        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("not found"))
    }

    @Test
    fun `tool metadata is correct`() {
        val tool = SearchCodeTool()
        assertEquals("search_code", tool.name)
        assertTrue(tool.parameters.required.contains("query"))
    }
}

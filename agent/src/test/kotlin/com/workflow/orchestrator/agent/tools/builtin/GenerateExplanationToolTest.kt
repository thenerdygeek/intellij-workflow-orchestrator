package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Tests for GenerateExplanationTool.
 *
 * Note: Tool execution requires git4idea (IntelliJ git plugin) which is
 * not available in unit tests. These tests verify:
 * - Tool metadata and parameter definitions
 * - Parameter validation (missing required params)
 * - Error handling for missing project git repo
 *
 * Integration tests with actual git repos would require IntelliJ's
 * heavyweight test framework (BasePlatformTestCase).
 */
class GenerateExplanationToolTest {

    private val project = mockk<Project>(relaxed = true)
    private val tool = GenerateExplanationTool()

    @Test
    fun `tool metadata is correct`() {
        assertEquals("generate_explanation", tool.name)
        assertTrue(tool.description.contains("explanation"))
        assertTrue(tool.description.contains("diff"))
    }

    @Test
    fun `required parameters are title and from_ref`() {
        val required = tool.parameters.required
        assertTrue("title" in required, "title should be required")
        assertTrue("from_ref" in required, "from_ref should be required")
        assertFalse("to_ref" in required, "to_ref should be optional")
    }

    @Test
    fun `all three parameters are defined`() {
        val props = tool.parameters.properties
        assertTrue("title" in props)
        assertTrue("from_ref" in props)
        assertTrue("to_ref" in props)
        assertEquals(3, props.size)
    }

    @Test
    fun `returns error when title is missing`() = runTest {
        val params = buildJsonObject {
            put("from_ref", "HEAD~1")
        }
        val result = tool.execute(params, project)
        assertTrue(result.isError)
        assertTrue(result.content.contains("title"))
    }

    @Test
    fun `returns error when from_ref is missing`() = runTest {
        val params = buildJsonObject {
            put("title", "My Changes")
        }
        val result = tool.execute(params, project)
        assertTrue(result.isError)
        assertTrue(result.content.contains("from_ref"))
    }

    @Test
    fun `returns error when both required params are missing`() = runTest {
        val params = buildJsonObject { }
        val result = tool.execute(params, project)
        assertTrue(result.isError)
    }

    @Test
    fun `handles execution error gracefully`() = runTest {
        // With a mocked project, GitRepositoryManager.getInstance will fail
        // This tests the try-catch error handling path
        val params = buildJsonObject {
            put("title", "Test Changes")
            put("from_ref", "HEAD~1")
        }
        val result = tool.execute(params, project)
        // Should return error (no real git repo), not throw exception
        assertTrue(result.isError)
    }

    @Test
    fun `handles execution with to_ref error gracefully`() = runTest {
        val params = buildJsonObject {
            put("title", "Branch Comparison")
            put("from_ref", "main")
            put("to_ref", "feature-branch")
        }
        val result = tool.execute(params, project)
        // Should return error (no real git repo), not throw exception
        assertTrue(result.isError)
    }

    @Test
    fun `allowed for coder analyzer and reviewer workers`() {
        assertTrue(tool.allowedWorkers.size >= 3)
    }
}

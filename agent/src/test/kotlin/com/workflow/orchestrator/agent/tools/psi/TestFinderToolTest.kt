package com.workflow.orchestrator.agent.tools.psi

import com.workflow.orchestrator.agent.ide.LanguageProviderRegistry
import com.workflow.orchestrator.agent.tools.WorkerType
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TestFinderToolTest {

    private val registry = LanguageProviderRegistry()
    private val tool = TestFinderTool(registry)

    @Test
    fun `tool metadata is correct`() {
        assertEquals("test_finder", tool.name)
        assertTrue(tool.description.contains("test class"))
        assertTrue(tool.description.contains("source class"))
        assertTrue(tool.description.contains("JUnit4"))
        assertTrue(tool.description.contains("JUnit5"))
        assertTrue(tool.description.contains("TestNG"))
        assertTrue(tool.description.contains("FooTest"))

        val props = tool.parameters.properties
        assertTrue(props.containsKey("file"))
        assertTrue(props.containsKey("class_name"))

        assertEquals(listOf("file"), tool.parameters.required)
    }

    @Test
    fun `allowed workers include ANALYZER, CODER, REVIEWER`() {
        assertEquals(
            setOf(WorkerType.ANALYZER, WorkerType.CODER, WorkerType.REVIEWER),
            tool.allowedWorkers
        )
    }

    @Test
    fun `error when file parameter is missing`() = runTest {
        val params = buildJsonObject { }
        val mockProject = mockk<com.intellij.openapi.project.Project>()
        val result = tool.execute(params, mockProject)
        assertTrue(result.isError)
        assertTrue(result.content.contains("'file' parameter is required"))
    }

    @Test
    fun `parameters have correct types`() {
        val props = tool.parameters.properties
        assertEquals("string", props["file"]?.type)
        assertEquals("string", props["class_name"]?.type)
    }

    @Test
    fun `tool definition can be generated`() {
        val definition = tool.toToolDefinition()
        assertEquals("test_finder", definition.function.name)
        assertEquals("function", definition.type)
        assertFalse(definition.function.description.isBlank())
        assertTrue(definition.function.parameters.properties.isNotEmpty())
    }

    @Test
    fun `class_name parameter is optional`() {
        assertFalse(tool.parameters.required.contains("class_name"))
    }

    @Test
    fun `execute returns dumbModeError when indexing`() = runTest {
        val project = mockk<com.intellij.openapi.project.Project> {
            every { basePath } returns "/tmp"
        }
        val params = buildJsonObject {
            put("file", "src/main/java/com/example/UserService.java")
        }

        mockkStatic(com.intellij.openapi.project.DumbService::class)
        every { com.intellij.openapi.project.DumbService.isDumb(project) } returns true

        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("indexing"))
        unmockkStatic(com.intellij.openapi.project.DumbService::class)
    }

    @Test
    fun `execute with path outside project returns security error`() = runTest {
        val project = mockk<com.intellij.openapi.project.Project> {
            every { basePath } returns "/tmp/myproject"
        }
        val params = buildJsonObject {
            put("file", "/etc/passwd")
        }

        mockkStatic(com.intellij.openapi.project.DumbService::class)
        every { com.intellij.openapi.project.DumbService.isDumb(project) } returns false

        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(
            result.content.contains("outside the project"),
            "Expected path security error, got: ${result.content}"
        )
        unmockkStatic(com.intellij.openapi.project.DumbService::class)
    }

    @Test
    fun `execute with null basePath returns error`() = runTest {
        val project = mockk<com.intellij.openapi.project.Project> {
            every { basePath } returns null
        }
        val params = buildJsonObject {
            put("file", "src/main/java/com/example/UserService.java")
            put("class_name", "UserService")
        }

        mockkStatic(com.intellij.openapi.project.DumbService::class)
        every { com.intellij.openapi.project.DumbService.isDumb(project) } returns false

        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(
            result.content.contains("project base path"),
            "Expected project path error, got: ${result.content}"
        )
        unmockkStatic(com.intellij.openapi.project.DumbService::class)
    }
}

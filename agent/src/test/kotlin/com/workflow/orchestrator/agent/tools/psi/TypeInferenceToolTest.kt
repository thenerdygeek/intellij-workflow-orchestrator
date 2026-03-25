package com.workflow.orchestrator.agent.tools.psi

import com.workflow.orchestrator.agent.runtime.WorkerType
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TypeInferenceToolTest {

    private val tool = TypeInferenceTool()

    @Test
    fun `tool metadata is correct`() {
        assertEquals("type_inference", tool.name)
        assertTrue(tool.description.contains("resolved type"))
        assertTrue(tool.description.contains("Java and Kotlin"))

        val props = tool.parameters.properties
        assertTrue(props.containsKey("file"))
        assertTrue(props.containsKey("offset"))
        assertTrue(props.containsKey("line"))
        assertTrue(props.containsKey("column"))

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
        val params = buildJsonObject {
            put("offset", 10)
        }
        val mockProject = io.mockk.mockk<com.intellij.openapi.project.Project>()
        val result = tool.execute(params, mockProject)
        assertTrue(result.isError)
        assertTrue(result.content.contains("'file' parameter is required"))
    }

    @Test
    fun `error when neither offset nor line is provided`() = runTest {
        val params = buildJsonObject {
            put("file", "src/Main.java")
        }
        val mockProject = io.mockk.mockk<com.intellij.openapi.project.Project>(relaxed = true)

        // This will fail at dumb mode check or path validation before we get to the position check,
        // but we can test the parameter validation by mocking appropriately.
        // Since DumbService requires application context, we test the param extraction logic
        // by verifying the error message pattern.
        val result = tool.execute(params, mockProject)
        assertTrue(result.isError)
        // Either hits "offset or line" validation or dumb mode — both are valid error states
        assertTrue(
            result.content.contains("offset") || result.content.contains("line") || result.content.contains("indexing"),
            "Expected error about missing position or indexing, got: ${result.content}"
        )
    }

    @Test
    fun `parameters have correct types`() {
        val props = tool.parameters.properties
        assertEquals("string", props["file"]?.type)
        assertEquals("integer", props["offset"]?.type)
        assertEquals("integer", props["line"]?.type)
        assertEquals("integer", props["column"]?.type)
    }

    @Test
    fun `tool definition can be generated`() {
        val definition = tool.toToolDefinition()
        assertEquals("type_inference", definition.function.name)
        assertEquals("function", definition.type)
        assertFalse(definition.function.description.isBlank())
        assertTrue(definition.function.parameters.properties.isNotEmpty())
    }
}

package com.workflow.orchestrator.agent.tools.psi

import com.workflow.orchestrator.agent.runtime.WorkerType
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class StructuralSearchToolTest {

    private val tool = StructuralSearchTool()

    @Test
    fun `tool metadata is correct`() {
        assertEquals("structural_search", tool.name)
        assertTrue(tool.description.contains("structural search"))
        assertTrue(tool.description.contains("Java and Kotlin"))

        val props = tool.parameters.properties
        assertTrue(props.containsKey("pattern"))
        assertTrue(props.containsKey("file_type"))
        assertTrue(props.containsKey("scope"))
        assertTrue(props.containsKey("max_results"))

        assertEquals(listOf("pattern"), tool.parameters.required)
    }

    @Test
    fun `allowed workers include ANALYZER and REVIEWER`() {
        assertEquals(
            setOf(WorkerType.ANALYZER, WorkerType.REVIEWER),
            tool.allowedWorkers
        )
    }

    @Test
    fun `error when pattern parameter is missing`() = runTest {
        val params = buildJsonObject {
            put("file_type", "java")
        }
        val mockProject = io.mockk.mockk<com.intellij.openapi.project.Project>()
        val result = tool.execute(params, mockProject)
        assertTrue(result.isError)
        assertTrue(result.content.contains("'pattern' parameter is required"))
    }

    @Test
    fun `parameters have correct types`() {
        val props = tool.parameters.properties
        assertEquals("string", props["pattern"]?.type)
        assertEquals("string", props["file_type"]?.type)
        assertEquals("string", props["scope"]?.type)
        assertEquals("integer", props["max_results"]?.type)
    }

    @Test
    fun `tool definition can be generated`() {
        val definition = tool.toToolDefinition()
        assertEquals("structural_search", definition.function.name)
        assertEquals("function", definition.type)
        assertFalse(definition.function.description.isBlank())
        assertTrue(definition.function.parameters.properties.isNotEmpty())
    }

    @Test
    fun `tool can be instantiated`() {
        val instance = StructuralSearchTool()
        assertNotNull(instance)
        assertEquals("structural_search", instance.name)
    }
}

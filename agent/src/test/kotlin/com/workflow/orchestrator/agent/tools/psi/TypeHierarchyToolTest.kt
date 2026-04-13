package com.workflow.orchestrator.agent.tools.psi

import com.workflow.orchestrator.agent.ide.LanguageProviderRegistry
import com.workflow.orchestrator.agent.tools.WorkerType
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TypeHierarchyToolTest {

    private val registry = LanguageProviderRegistry()

    @Test
    fun `tool metadata is correct`() {
        val tool = TypeHierarchyTool(registry)
        assertEquals("type_hierarchy", tool.name)
        assertTrue(tool.parameters.required.contains("class_name"))
        assertTrue(tool.parameters.properties.containsKey("class_name"))
    }

    @Test
    fun `allowedWorkers includes ANALYZER and REVIEWER`() {
        val tool = TypeHierarchyTool(registry)
        assertEquals(setOf(WorkerType.ANALYZER, WorkerType.REVIEWER), tool.allowedWorkers)
    }

    @Test
    fun `toToolDefinition produces valid schema`() {
        val tool = TypeHierarchyTool(registry)
        val def = tool.toToolDefinition()
        assertEquals("function", def.type)
        assertEquals("type_hierarchy", def.function.name)
        assertTrue(def.function.parameters.required.contains("class_name"))
    }

    @Test
    fun `execute returns dumbModeError when indexing`() = runTest {
        val tool = TypeHierarchyTool(registry)
        val project = mockk<com.intellij.openapi.project.Project> {
            every { basePath } returns "/tmp"
        }
        val params = kotlinx.serialization.json.buildJsonObject {
            put("class_name", kotlinx.serialization.json.JsonPrimitive("MyClass"))
        }

        mockkStatic(com.intellij.openapi.project.DumbService::class)
        every { com.intellij.openapi.project.DumbService.isDumb(project) } returns true

        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("indexing"))
        unmockkStatic(com.intellij.openapi.project.DumbService::class)
    }

    @Test
    fun `execute returns error when class_name is missing`() = runTest {
        val tool = TypeHierarchyTool(registry)
        val project = mockk<com.intellij.openapi.project.Project> {
            every { basePath } returns "/tmp"
        }
        val params = kotlinx.serialization.json.buildJsonObject { }

        mockkStatic(com.intellij.openapi.project.DumbService::class)
        every { com.intellij.openapi.project.DumbService.isDumb(project) } returns false

        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("'class_name' parameter required"))
        unmockkStatic(com.intellij.openapi.project.DumbService::class)
    }
}

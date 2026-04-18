package com.workflow.orchestrator.agent.tools.psi

import com.workflow.orchestrator.agent.ide.LanguageProviderRegistry
import com.workflow.orchestrator.agent.tools.WorkerType
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FindReferencesToolTest {

    private val registry = LanguageProviderRegistry()

    @Test
    fun `tool metadata is correct`() {
        val tool = FindReferencesTool(registry)
        assertEquals("find_references", tool.name)
        assertTrue(tool.parameters.required.contains("symbol"))
        assertTrue(tool.parameters.properties.containsKey("symbol"))
        assertTrue(tool.parameters.properties.containsKey("file"))
    }

    @Test
    fun `allowedWorkers includes ANALYZER and REVIEWER`() {
        val tool = FindReferencesTool(registry)
        assertEquals(setOf(WorkerType.ANALYZER, WorkerType.REVIEWER), tool.allowedWorkers)
    }

    @Test
    fun `toToolDefinition produces valid schema`() {
        val tool = FindReferencesTool(registry)
        val def = tool.toToolDefinition()
        assertEquals("function", def.type)
        assertEquals("find_references", def.function.name)
        assertTrue(def.function.parameters.required.contains("symbol"))
        assertFalse(def.function.parameters.required.contains("file"))
    }

    @Test
    fun `execute returns dumbModeError when indexing`() = runTest {
        val tool = FindReferencesTool(registry)
        val project = mockk<com.intellij.openapi.project.Project> {
            every { basePath } returns "/tmp"
        }
        val params = kotlinx.serialization.json.buildJsonObject {
            put("symbol", kotlinx.serialization.json.JsonPrimitive("MyClass"))
        }

        mockkStatic(com.intellij.openapi.project.DumbService::class)
        every { com.intellij.openapi.project.DumbService.isDumb(project) } returns true

        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("indexing"))
        unmockkStatic(com.intellij.openapi.project.DumbService::class)
    }

    @Test
    fun `execute returns error when symbol is missing`() = runTest {
        val tool = FindReferencesTool(registry)
        val project = mockk<com.intellij.openapi.project.Project> {
            every { basePath } returns "/tmp"
        }
        val params = kotlinx.serialization.json.buildJsonObject { }

        mockkStatic(com.intellij.openapi.project.DumbService::class)
        every { com.intellij.openapi.project.DumbService.isDumb(project) } returns false

        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("'symbol' parameter required"))
        unmockkStatic(com.intellij.openapi.project.DumbService::class)
    }
}

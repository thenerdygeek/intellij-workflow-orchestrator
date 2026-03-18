package com.workflow.orchestrator.agent.tools.psi

import com.workflow.orchestrator.agent.runtime.WorkerType
import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CallHierarchyToolTest {

    @Test
    fun `tool metadata is correct`() {
        val tool = CallHierarchyTool()
        assertEquals("call_hierarchy", tool.name)
        assertTrue(tool.parameters.required.contains("method"))
        assertTrue(tool.parameters.properties.containsKey("method"))
        assertTrue(tool.parameters.properties.containsKey("class_name"))
    }

    @Test
    fun `allowedWorkers is ANALYZER only`() {
        val tool = CallHierarchyTool()
        assertEquals(setOf(WorkerType.ANALYZER), tool.allowedWorkers)
    }

    @Test
    fun `class_name is optional parameter`() {
        val tool = CallHierarchyTool()
        assertTrue(tool.parameters.required.contains("method"))
        assertFalse(tool.parameters.required.contains("class_name"))
    }

    @Test
    fun `toToolDefinition produces valid schema`() {
        val tool = CallHierarchyTool()
        val def = tool.toToolDefinition()
        assertEquals("function", def.type)
        assertEquals("call_hierarchy", def.function.name)
        assertTrue(def.function.parameters.required.contains("method"))
        assertEquals(2, def.function.parameters.properties.size)
    }

    @Test
    fun `execute returns dumbModeError when indexing`() = kotlinx.coroutines.test.runTest {
        val tool = CallHierarchyTool()
        val project = mockk<com.intellij.openapi.project.Project> {
            every { basePath } returns "/tmp"
        }
        val params = kotlinx.serialization.json.buildJsonObject {
            put("method", kotlinx.serialization.json.JsonPrimitive("doSomething"))
        }

        mockkStatic(com.intellij.openapi.project.DumbService::class)
        every { com.intellij.openapi.project.DumbService.isDumb(project) } returns true

        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("indexing"))
        unmockkStatic(com.intellij.openapi.project.DumbService::class)
    }

    @Test
    fun `execute returns error when method is missing`() = kotlinx.coroutines.test.runTest {
        val tool = CallHierarchyTool()
        val project = mockk<com.intellij.openapi.project.Project> {
            every { basePath } returns "/tmp"
        }
        val params = kotlinx.serialization.json.buildJsonObject { }

        mockkStatic(com.intellij.openapi.project.DumbService::class)
        every { com.intellij.openapi.project.DumbService.isDumb(project) } returns false

        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("'method' parameter required"))
        unmockkStatic(com.intellij.openapi.project.DumbService::class)
    }
}

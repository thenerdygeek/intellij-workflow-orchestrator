package com.workflow.orchestrator.agent.tools.psi

import com.workflow.orchestrator.agent.runtime.WorkerType
import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FileStructureToolTest {

    @Test
    fun `tool metadata is correct`() {
        val tool = FileStructureTool()
        assertEquals("file_structure", tool.name)
        assertTrue(tool.parameters.required.contains("path"))
        assertTrue(tool.parameters.properties.containsKey("path"))
        assertEquals(2, tool.parameters.properties.size)
    }

    @Test
    fun `allowedWorkers includes ANALYZER, CODER, REVIEWER, ORCHESTRATOR`() {
        val tool = FileStructureTool()
        assertEquals(
            setOf(WorkerType.ANALYZER, WorkerType.CODER, WorkerType.REVIEWER, WorkerType.ORCHESTRATOR),
            tool.allowedWorkers
        )
    }

    @Test
    fun `toToolDefinition produces valid schema`() {
        val tool = FileStructureTool()
        val def = tool.toToolDefinition()
        assertEquals("function", def.type)
        assertEquals("file_structure", def.function.name)
        assertTrue(def.function.description.isNotBlank())
        assertTrue(def.function.parameters.properties.containsKey("path"))
    }

    @Test
    fun `execute returns dumbModeError when indexing`() = kotlinx.coroutines.test.runTest {
        val tool = FileStructureTool()
        val project = mockk<com.intellij.openapi.project.Project> {
            every { basePath } returns "/tmp"
        }
        val params = kotlinx.serialization.json.buildJsonObject {
            put("path", kotlinx.serialization.json.JsonPrimitive("/some/file.java"))
        }

        mockkStatic(com.intellij.openapi.project.DumbService::class)
        every { com.intellij.openapi.project.DumbService.isDumb(project) } returns true

        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("indexing"))
        unmockkStatic(com.intellij.openapi.project.DumbService::class)
    }

    @Test
    fun `execute returns error when path is missing`() = kotlinx.coroutines.test.runTest {
        val tool = FileStructureTool()
        val project = mockk<com.intellij.openapi.project.Project> {
            every { basePath } returns "/tmp"
        }
        val params = kotlinx.serialization.json.buildJsonObject { }

        mockkStatic(com.intellij.openapi.project.DumbService::class)
        every { com.intellij.openapi.project.DumbService.isDumb(project) } returns false

        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("'path' required"))
        unmockkStatic(com.intellij.openapi.project.DumbService::class)
    }
}

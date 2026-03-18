package com.workflow.orchestrator.agent.tools.psi

import com.workflow.orchestrator.agent.runtime.WorkerType
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SpringContextToolTest {

    @Test
    fun `tool metadata is correct`() {
        val tool = SpringContextTool()
        assertEquals("spring_context", tool.name)
        assertTrue(tool.description.contains("Spring beans"))
        assertTrue(tool.parameters.properties.containsKey("filter"))
        assertTrue(tool.parameters.required.isEmpty())
    }

    @Test
    fun `allowedWorkers includes ANALYZER and ORCHESTRATOR`() {
        val tool = SpringContextTool()
        assertEquals(setOf(WorkerType.ANALYZER, WorkerType.ORCHESTRATOR), tool.allowedWorkers)
    }

    @Test
    fun `toToolDefinition produces valid schema`() {
        val tool = SpringContextTool()
        val def = tool.toToolDefinition()
        assertEquals("function", def.type)
        assertEquals("spring_context", def.function.name)
        assertTrue(def.function.description.isNotBlank())
        assertEquals("object", def.function.parameters.type)
        assertTrue(def.function.parameters.properties.containsKey("filter"))
        assertEquals("string", def.function.parameters.properties["filter"]?.type)
    }

    @Test
    fun `execute returns dumbModeError when indexing`() = runTest {
        val tool = SpringContextTool()
        val project = mockk<com.intellij.openapi.project.Project>()
        val params = buildJsonObject {
            put("filter", JsonPrimitive("Service"))
        }

        mockkStatic(com.intellij.openapi.project.DumbService::class)
        every { com.intellij.openapi.project.DumbService.isDumb(project) } returns true

        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("indexing"))
        unmockkStatic(com.intellij.openapi.project.DumbService::class)
    }

    @Test
    fun `filter parameter is optional`() {
        val tool = SpringContextTool()
        assertTrue(tool.parameters.required.isEmpty())
    }
}

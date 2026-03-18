package com.workflow.orchestrator.agent.tools.psi

import com.workflow.orchestrator.agent.runtime.WorkerType
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SpringBeanGraphToolTest {

    @Test
    fun `tool metadata is correct`() {
        val tool = SpringBeanGraphTool()
        assertEquals("spring_bean_graph", tool.name)
        assertTrue(tool.description.contains("dependency injection graph"))
        assertTrue(tool.parameters.properties.containsKey("bean_name"))
        assertEquals(listOf("bean_name"), tool.parameters.required)
    }

    @Test
    fun `allowedWorkers includes ANALYZER and ORCHESTRATOR`() {
        val tool = SpringBeanGraphTool()
        assertEquals(setOf(WorkerType.ANALYZER, WorkerType.ORCHESTRATOR), tool.allowedWorkers)
    }

    @Test
    fun `toToolDefinition produces valid schema`() {
        val tool = SpringBeanGraphTool()
        val def = tool.toToolDefinition()
        assertEquals("function", def.type)
        assertEquals("spring_bean_graph", def.function.name)
        assertTrue(def.function.description.isNotBlank())
        assertEquals("object", def.function.parameters.type)
        assertTrue(def.function.parameters.properties.containsKey("bean_name"))
        assertEquals("string", def.function.parameters.properties["bean_name"]?.type)
        assertEquals(listOf("bean_name"), def.function.parameters.required)
    }

    @Test
    fun `execute returns dumbModeError when indexing`() = runTest {
        val tool = SpringBeanGraphTool()
        val project = mockk<com.intellij.openapi.project.Project>()
        val params = buildJsonObject {
            put("bean_name", JsonPrimitive("UserService"))
        }

        mockkStatic(com.intellij.openapi.project.DumbService::class)
        every { com.intellij.openapi.project.DumbService.isDumb(project) } returns true

        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("indexing"))
        unmockkStatic(com.intellij.openapi.project.DumbService::class)
    }

    @Test
    fun `execute returns error when bean_name is missing`() = runTest {
        val tool = SpringBeanGraphTool()
        val project = mockk<com.intellij.openapi.project.Project>()
        val params = buildJsonObject { }

        mockkStatic(com.intellij.openapi.project.DumbService::class)
        every { com.intellij.openapi.project.DumbService.isDumb(project) } returns false

        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("'bean_name' parameter required"))
        unmockkStatic(com.intellij.openapi.project.DumbService::class)
    }

    @Test
    fun `bean_name is required parameter`() {
        val tool = SpringBeanGraphTool()
        assertTrue(tool.parameters.required.contains("bean_name"))
        assertEquals(1, tool.parameters.required.size)
    }
}

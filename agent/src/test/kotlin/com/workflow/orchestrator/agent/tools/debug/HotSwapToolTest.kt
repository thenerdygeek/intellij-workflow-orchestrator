package com.workflow.orchestrator.agent.tools.debug

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.runtime.WorkerType
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class HotSwapToolTest {
    private val controller = mockk<AgentDebugController>(relaxed = true)
    private val project = mockk<Project>(relaxed = true)
    private val tool = HotSwapTool(controller)

    @Test
    fun `metadata is correct`() {
        assertEquals("hotswap", tool.name)
        assertTrue(tool.description.contains("Apply code changes"))
        assertTrue(tool.description.contains("without restarting"))
    }

    @Test
    fun `all parameters are optional`() {
        assertTrue(tool.parameters.required.isEmpty())
        assertTrue(tool.parameters.properties.containsKey("session_id"))
        assertTrue(tool.parameters.properties.containsKey("compile_first"))
    }

    @Test
    fun `compile_first parameter is boolean type`() {
        assertEquals("boolean", tool.parameters.properties["compile_first"]?.type)
    }

    @Test
    fun `allowed workers include CODER only`() {
        assertEquals(setOf(WorkerType.CODER), tool.allowedWorkers)
    }

    @Test
    fun `returns error when no session found`() = runTest {
        every { controller.getActiveSessionId() } returns null
        every { controller.getSession(null) } returns null

        val result = tool.execute(buildJsonObject { }, project)
        assertTrue(result.isError)
        assertTrue(result.content.contains("No debug session found"))
    }

    @Test
    fun `returns error when specified session not found`() = runTest {
        every { controller.getSession("bad-id") } returns null

        val result = tool.execute(buildJsonObject {
            put("session_id", "bad-id")
        }, project)
        assertTrue(result.isError)
        assertTrue(result.content.contains("No debug session found"))
        assertTrue(result.content.contains("bad-id"))
    }

    @Test
    fun `produces valid tool definition`() {
        val def = tool.toToolDefinition()
        assertEquals("function", def.type)
        assertEquals("hotswap", def.function.name)
        assertTrue(def.function.description.isNotBlank())
        assertEquals("object", def.function.parameters.type)
    }
}

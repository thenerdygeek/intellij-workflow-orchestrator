package com.workflow.orchestrator.agent.tools.debug

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.WorkerType
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DebugInspectToolTest {
    private val project = mockk<Project>(relaxed = true)
    private val controller = mockk<AgentDebugController>(relaxed = true)
    private val tool = DebugInspectTool(controller)

    @Test
    fun `tool name is debug_inspect`() {
        assertEquals("debug_inspect", tool.name)
    }

    @Test
    fun `action enum contains all 9 actions`() {
        val actions = tool.parameters.properties["action"]?.enumValues
        assertNotNull(actions)
        assertEquals(9, actions!!.size)
        assertTrue("evaluate" in actions)
        assertTrue("get_stack_frames" in actions)
        assertTrue("get_variables" in actions)
        assertTrue("set_value" in actions)
        assertTrue("thread_dump" in actions)
        assertTrue("memory_view" in actions)
        assertTrue("hotswap" in actions)
        assertTrue("force_return" in actions)
        assertTrue("drop_frame" in actions)
    }

    @Test
    fun `only action is required`() {
        assertEquals(listOf("action"), tool.parameters.required)
    }

    @Test
    fun `allowedWorkers includes CODER, REVIEWER, ANALYZER`() {
        assertEquals(
            setOf(WorkerType.CODER, WorkerType.REVIEWER, WorkerType.ANALYZER),
            tool.allowedWorkers
        )
    }

    @Test
    fun `toToolDefinition produces valid schema`() {
        val def = tool.toToolDefinition()
        assertEquals("function", def.type)
        assertEquals("debug_inspect", def.function.name)
        assertEquals("object", def.function.parameters.type)
        assertTrue(def.function.description.isNotBlank())
    }

    @Test
    fun `missing action returns error`() = runTest {
        val result = tool.execute(buildJsonObject { }, project)
        assertTrue(result.isError)
        assertTrue(result.content.contains("action"))
    }

    @Test
    fun `unknown action returns error`() = runTest {
        val result = tool.execute(buildJsonObject { put("action", "nonexistent") }, project)
        assertTrue(result.isError)
        assertTrue(result.content.contains("Unknown action"))
    }
}

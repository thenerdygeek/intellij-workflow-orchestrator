package com.workflow.orchestrator.agent.tools.debug

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.WorkerType
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DebugStepToolTest {
    private val project = mockk<Project>(relaxed = true)
    private val controller = mockk<AgentDebugController>(relaxed = true)
    private val tool = DebugStepTool(controller)

    @Test
    fun `tool name is debug_step`() {
        assertEquals("debug_step", tool.name)
    }

    @Test
    fun `action enum contains all 10 actions`() {
        val actions = tool.parameters.properties["action"]?.enumValues
        assertNotNull(actions)
        assertEquals(10, actions!!.size)
        assertTrue("get_state" in actions)
        assertTrue("step_over" in actions)
        assertTrue("step_into" in actions)
        assertTrue("step_out" in actions)
        assertTrue("force_step_into" in actions)
        assertTrue("force_step_over" in actions)
        assertTrue("resume" in actions)
        assertTrue("pause" in actions)
        assertTrue("run_to_cursor" in actions)
        assertTrue("stop" in actions)
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
        assertEquals("debug_step", def.function.name)
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

package com.workflow.orchestrator.agent.tools.debug

import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebuggerManager
import com.workflow.orchestrator.agent.tools.WorkerType
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
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

    @Test
    fun `get_variables on running session returns message mentioning session_id and get_state`() = runTest {
        // Arrange: a running (not paused) session is the only one
        val runningSession = mockk<XDebugSession>(relaxed = true) {
            every { isSuspended } returns false
            every { currentStackFrame } returns null
            every { suspendContext } returns null
        }
        mockkStatic(XDebuggerManager::class)
        val mgr = mockk<XDebuggerManager>(relaxed = true)
        every { XDebuggerManager.getInstance(project) } returns mgr
        every { mgr.debugSessions } returns arrayOf(runningSession)
        every { mgr.currentSession } returns runningSession

        // Act
        val result = tool.execute(buildJsonObject { put("action", "get_variables") }, project)

        // Assert
        assertTrue(result.isError)
        val content = result.content
        assertTrue(content.contains("session_id"), "expected 'session_id' in message: $content")
        assertTrue(content.contains("get_state"), "expected 'get_state' hint in message: $content")
        // Also confirm it still communicates the suspension requirement
        assertTrue(content.contains("paused", ignoreCase = true) || content.contains("suspend", ignoreCase = true),
            "expected message to still mention paused/suspended requirement: $content")

        unmockkStatic(XDebuggerManager::class)
    }
}

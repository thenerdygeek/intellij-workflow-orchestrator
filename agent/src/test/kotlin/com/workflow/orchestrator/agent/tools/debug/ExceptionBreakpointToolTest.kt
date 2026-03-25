package com.workflow.orchestrator.agent.tools.debug

import com.workflow.orchestrator.agent.runtime.WorkerType
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ExceptionBreakpointToolTest {

    private val controller = io.mockk.mockk<AgentDebugController>(relaxed = true)
    private val tool = ExceptionBreakpointTool(controller)

    @Test
    fun `tool name is exception_breakpoint`() {
        assertEquals("exception_breakpoint", tool.name)
    }

    @Test
    fun `tool description mentions exception breakpoint`() {
        assertTrue(tool.description.contains("exception"))
        assertTrue(tool.description.contains("breakpoint"))
    }

    @Test
    fun `parameters include exception_class as required`() {
        assertTrue(tool.parameters.required.contains("exception_class"))
        assertNotNull(tool.parameters.properties["exception_class"])
    }

    @Test
    fun `parameters include optional caught, uncaught, and condition`() {
        assertNotNull(tool.parameters.properties["caught"])
        assertNotNull(tool.parameters.properties["uncaught"])
        assertNotNull(tool.parameters.properties["condition"])
        assertFalse(tool.parameters.required.contains("caught"))
        assertFalse(tool.parameters.required.contains("uncaught"))
        assertFalse(tool.parameters.required.contains("condition"))
    }

    @Test
    fun `allowed workers includes CODER`() {
        assertTrue(tool.allowedWorkers.contains(WorkerType.CODER))
        assertEquals(setOf(WorkerType.CODER), tool.allowedWorkers)
    }

    @Test
    fun `exception_class parameter type is string`() {
        assertEquals("string", tool.parameters.properties["exception_class"]?.type)
    }

    @Test
    fun `caught parameter type is boolean`() {
        assertEquals("boolean", tool.parameters.properties["caught"]?.type)
    }

    @Test
    fun `uncaught parameter type is boolean`() {
        assertEquals("boolean", tool.parameters.properties["uncaught"]?.type)
    }

    @Test
    fun `condition parameter type is string`() {
        assertEquals("string", tool.parameters.properties["condition"]?.type)
    }
}

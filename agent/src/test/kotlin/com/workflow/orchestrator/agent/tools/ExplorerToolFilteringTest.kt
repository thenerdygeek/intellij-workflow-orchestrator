package com.workflow.orchestrator.agent.tools

import com.workflow.orchestrator.agent.tools.debug.AgentDebugController
import com.workflow.orchestrator.agent.tools.debug.DebugBreakpointsTool
import com.workflow.orchestrator.agent.tools.debug.DebugStepTool
import com.workflow.orchestrator.agent.tools.debug.DebugInspectTool
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class ExplorerToolFilteringTest {

    private val mockDebugController = mockk<AgentDebugController>(relaxed = true)

    @Test
    fun `debug meta-tools include analyzer for read-only actions`() {
        val debugMetaTools = listOf(
            DebugBreakpointsTool(mockDebugController),
            DebugStepTool(mockDebugController),
            DebugInspectTool(mockDebugController)
        )
        for (tool in debugMetaTools) {
            assertTrue(
                WorkerType.ANALYZER in tool.allowedWorkers,
                "Debug meta-tool ${tool.name} should include ANALYZER for read-only actions"
            )
        }
    }
}

package com.workflow.orchestrator.agent.tools

import com.workflow.orchestrator.agent.runtime.ApprovalGate
import com.workflow.orchestrator.agent.runtime.RiskLevel
import com.workflow.orchestrator.agent.runtime.WorkerType
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
        // Debug meta-tools include ANALYZER since they expose read-only actions
        // (list_breakpoints, get_state, get_stack_frames, get_variables, etc.)
        // alongside write actions. Action-level gating handles safety.
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

    @Test
    fun `all explorer-accessible tools are NONE risk`() {
        val explorerReadOnlyTools = listOf(
            "read_file", "search_code", "glob_files", "file_structure",
            "find_definition", "find_references", "type_hierarchy",
            "call_hierarchy", "find_implementations",
            "diagnostics", "think"
        )
        for (toolName in explorerReadOnlyTools) {
            val risk = ApprovalGate.riskLevelFor(toolName)
            assertEquals(
                RiskLevel.NONE, risk,
                "Explorer tool '$toolName' should be NONE risk, but was $risk"
            )
        }

        val metaToolReadOnlyActions = mapOf(
            "git" to listOf("status", "blame", "diff", "log", "branches", "show_file", "show_commit")
        )
        for ((toolName, actions) in metaToolReadOnlyActions) {
            for (action in actions) {
                val risk = ApprovalGate.classifyRisk(toolName, mapOf("action" to action))
                assertEquals(
                    RiskLevel.NONE, risk,
                    "Explorer meta-tool '$toolName' action '$action' should be NONE risk, but was $risk"
                )
            }
        }
    }
}

package com.workflow.orchestrator.agent.tools

import com.workflow.orchestrator.agent.runtime.ApprovalGate
import com.workflow.orchestrator.agent.runtime.RiskLevel
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.debug.AgentDebugController
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class ExplorerToolFilteringTest {

    private val mockDebugController = mockk<AgentDebugController>(relaxed = true)

    private fun instantiateTool(className: String): AgentTool {
        val clazz = Class.forName(className)
        return try {
            clazz.getDeclaredConstructor().newInstance() as AgentTool
        } catch (_: NoSuchMethodException) {
            clazz.getDeclaredConstructor(AgentDebugController::class.java)
                .newInstance(mockDebugController) as AgentTool
        }
    }

    @Test
    fun `explorer does not have debug action tools`() {
        val debugActionClasses = listOf(
            "com.workflow.orchestrator.agent.tools.debug.AddBreakpointTool",
            "com.workflow.orchestrator.agent.tools.debug.RemoveBreakpointTool",
            "com.workflow.orchestrator.agent.tools.debug.StartDebugSessionTool",
            "com.workflow.orchestrator.agent.tools.debug.DebugStepOverTool",
            "com.workflow.orchestrator.agent.tools.debug.DebugStepIntoTool",
            "com.workflow.orchestrator.agent.tools.debug.DebugStepOutTool",
            "com.workflow.orchestrator.agent.tools.debug.DebugResumeTool",
            "com.workflow.orchestrator.agent.tools.debug.DebugPauseTool",
            "com.workflow.orchestrator.agent.tools.debug.DebugRunToCursorTool",
            "com.workflow.orchestrator.agent.tools.debug.DebugStopTool",
            "com.workflow.orchestrator.agent.tools.debug.EvaluateExpressionTool"
        )
        for (className in debugActionClasses) {
            val tool = instantiateTool(className)
            assertFalse(
                WorkerType.ANALYZER in tool.allowedWorkers,
                "Explorer (ANALYZER) should NOT have access to ${tool.name}"
            )
        }
    }

    @Test
    fun `explorer does not have config mutation tools`() {
        val configToolClasses = listOf(
            "com.workflow.orchestrator.agent.tools.config.CreateRunConfigTool",
            "com.workflow.orchestrator.agent.tools.config.ModifyRunConfigTool",
            "com.workflow.orchestrator.agent.tools.config.DeleteRunConfigTool"
        )
        for (className in configToolClasses) {
            val tool = instantiateTool(className)
            assertFalse(
                WorkerType.ANALYZER in tool.allowedWorkers,
                "Explorer (ANALYZER) should NOT have access to ${tool.name}"
            )
        }
    }

    @Test
    fun `explorer retains read-only debug inspection tools`() {
        val readOnlyDebugClasses = listOf(
            "com.workflow.orchestrator.agent.tools.debug.ListBreakpointsTool",
            "com.workflow.orchestrator.agent.tools.debug.GetDebugStateTool",
            "com.workflow.orchestrator.agent.tools.debug.GetStackFramesTool",
            "com.workflow.orchestrator.agent.tools.debug.GetVariablesTool"
        )
        for (className in readOnlyDebugClasses) {
            val tool = instantiateTool(className)
            assertTrue(
                WorkerType.ANALYZER in tool.allowedWorkers,
                "Explorer (ANALYZER) SHOULD have access to ${tool.name} (read-only)"
            )
        }
    }

    @Test
    fun `all explorer-accessible tools are NONE risk`() {
        val explorerReadOnlyTools = listOf(
            "read_file", "search_code", "glob_files", "file_structure",
            "find_definition", "find_references", "type_hierarchy",
            "call_hierarchy", "find_implementations",
            "git_status", "git_blame", "git_diff", "git_log",
            "git_branches", "git_show_file", "git_show_commit",
            "list_breakpoints", "get_debug_state", "get_stack_frames", "get_variables",
            "diagnostics", "think"
        )
        for (toolName in explorerReadOnlyTools) {
            val risk = ApprovalGate.riskLevelFor(toolName)
            assertEquals(
                RiskLevel.NONE, risk,
                "Explorer tool '$toolName' should be NONE risk, but was $risk"
            )
        }
    }
}

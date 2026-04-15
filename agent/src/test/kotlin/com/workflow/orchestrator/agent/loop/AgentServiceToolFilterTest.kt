package com.workflow.orchestrator.agent.loop

import com.workflow.orchestrator.agent.tools.builtin.DiscardPlanTool
import com.workflow.orchestrator.agent.tools.builtin.PlanModeRespondTool
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Tests the plan-mode / act-mode tool schema filter implemented in AgentService.
 *
 * The filter (from AgentService.executeTask's toolDefinitionProvider lambda):
 * - Plan mode: exclude write tools + "enable_plan_mode"; keep "plan_mode_respond" + "discard_plan"
 * - Act mode: exclude "plan_mode_respond" + "discard_plan"; keep all other tools
 *
 * These tests exercise the exact predicate logic without requiring the full AgentService
 * to be instantiated (which depends on the IntelliJ platform).
 */
class AgentServiceToolFilterTest {

    /**
     * Replicates the tool-name filter from AgentService.executeTask's toolDefinitionProvider lambda.
     * This is the single source of truth for what the LLM can see in each mode.
     */
    private val writeToolNames = setOf(
        "edit_file", "create_file", "run_command", "revert_file",
        "kill_process", "send_stdin", "format_code", "optimize_imports",
        "refactor_rename"
    )

    private fun filterForMode(isPlanMode: Boolean, toolName: String): Boolean {
        return if (isPlanMode) {
            toolName !in writeToolNames && toolName != "enable_plan_mode"
        } else {
            toolName != "plan_mode_respond" && toolName != "discard_plan"
        }
    }

    private val allToolNames = listOf(
        "read_file", "edit_file", "create_file", "search_code", "glob_files",
        "run_command", "revert_file", "attempt_completion", "think",
        "ask_followup_question", "plan_mode_respond", "enable_plan_mode",
        "discard_plan", "use_skill", "new_task", "render_artifact",
        "find_definition", "find_references", "diagnostics", "tool_search", "agent"
    )

    // ---- Act mode tests ----

    @Test
    fun `act mode excludes discard_plan`() {
        val actModeTools = allToolNames.filter { filterForMode(isPlanMode = false, toolName = it) }

        assertFalse(
            actModeTools.contains("discard_plan"),
            "discard_plan must NOT be included in act mode tool definitions"
        )
    }

    @Test
    fun `act mode excludes plan_mode_respond`() {
        val actModeTools = allToolNames.filter { filterForMode(isPlanMode = false, toolName = it) }

        assertFalse(
            actModeTools.contains("plan_mode_respond"),
            "plan_mode_respond must NOT be included in act mode tool definitions"
        )
    }

    @Test
    fun `act mode includes write tools`() {
        val actModeTools = allToolNames.filter { filterForMode(isPlanMode = false, toolName = it) }

        assertTrue(actModeTools.contains("edit_file"), "edit_file should be available in act mode")
        assertTrue(actModeTools.contains("create_file"), "create_file should be available in act mode")
        assertTrue(actModeTools.contains("run_command"), "run_command should be available in act mode")
    }

    @Test
    fun `act mode includes enable_plan_mode`() {
        val actModeTools = allToolNames.filter { filterForMode(isPlanMode = false, toolName = it) }

        assertTrue(
            actModeTools.contains("enable_plan_mode"),
            "enable_plan_mode should be available in act mode so LLM can enter plan mode"
        )
    }

    @Test
    fun `act mode includes read and navigation tools`() {
        val actModeTools = allToolNames.filter { filterForMode(isPlanMode = false, toolName = it) }

        assertTrue(actModeTools.contains("read_file"))
        assertTrue(actModeTools.contains("search_code"))
        assertTrue(actModeTools.contains("attempt_completion"))
        assertTrue(actModeTools.contains("find_definition"))
        assertTrue(actModeTools.contains("find_references"))
        assertTrue(actModeTools.contains("diagnostics"))
    }

    // ---- Plan mode tests ----

    @Test
    fun `plan mode includes discard_plan`() {
        val planModeTools = allToolNames.filter { filterForMode(isPlanMode = true, toolName = it) }

        assertTrue(
            planModeTools.contains("discard_plan"),
            "discard_plan must be included in plan mode so LLM can clear a stale plan"
        )
    }

    @Test
    fun `plan mode includes plan_mode_respond`() {
        val planModeTools = allToolNames.filter { filterForMode(isPlanMode = true, toolName = it) }

        assertTrue(
            planModeTools.contains("plan_mode_respond"),
            "plan_mode_respond must be included in plan mode"
        )
    }

    @Test
    fun `plan mode excludes write tools`() {
        val planModeTools = allToolNames.filter { filterForMode(isPlanMode = true, toolName = it) }

        assertFalse(planModeTools.contains("edit_file"), "edit_file must be blocked in plan mode")
        assertFalse(planModeTools.contains("create_file"), "create_file must be blocked in plan mode")
        assertFalse(planModeTools.contains("run_command"), "run_command must be blocked in plan mode")
        assertFalse(planModeTools.contains("revert_file"), "revert_file must be blocked in plan mode")
    }

    @Test
    fun `plan mode excludes enable_plan_mode`() {
        val planModeTools = allToolNames.filter { filterForMode(isPlanMode = true, toolName = it) }

        assertFalse(
            planModeTools.contains("enable_plan_mode"),
            "enable_plan_mode should not appear in plan mode (already active)"
        )
    }

    @Test
    fun `plan mode includes read and navigation tools`() {
        val planModeTools = allToolNames.filter { filterForMode(isPlanMode = true, toolName = it) }

        assertTrue(planModeTools.contains("read_file"))
        assertTrue(planModeTools.contains("search_code"))
        assertTrue(planModeTools.contains("find_definition"))
        assertTrue(planModeTools.contains("find_references"))
        assertTrue(planModeTools.contains("diagnostics"))
        assertTrue(planModeTools.contains("attempt_completion"))
    }

    // ---- Tool object sanity tests (verify tool names match what the filter expects) ----

    @Test
    fun `DiscardPlanTool name matches the filter constant`() {
        assertEquals("discard_plan", DiscardPlanTool().name,
            "DiscardPlanTool.name must be 'discard_plan' to match AgentService filter")
    }

    @Test
    fun `PlanModeRespondTool name matches the filter constant`() {
        assertEquals("plan_mode_respond", PlanModeRespondTool().name,
            "PlanModeRespondTool.name must be 'plan_mode_respond' to match AgentService filter")
    }
}

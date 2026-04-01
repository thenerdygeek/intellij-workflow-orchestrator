package com.workflow.orchestrator.agent.runtime

import com.workflow.orchestrator.agent.api.dto.FunctionDefinition
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ToolDefinition
import com.workflow.orchestrator.agent.tools.AgentTool
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PlanModeToolFilterTest {

    // --- PLAN_MODE_BLOCKED_TOOLS contains all mutation tools ---

    @Test
    fun `PLAN_MODE_BLOCKED_TOOLS contains edit_file`() {
        assertTrue("edit_file" in SingleAgentSession.PLAN_MODE_BLOCKED_TOOLS)
    }

    @Test
    fun `PLAN_MODE_BLOCKED_TOOLS contains create_file`() {
        assertTrue("create_file" in SingleAgentSession.PLAN_MODE_BLOCKED_TOOLS)
    }

    @Test
    fun `PLAN_MODE_BLOCKED_TOOLS contains format_code`() {
        assertTrue("format_code" in SingleAgentSession.PLAN_MODE_BLOCKED_TOOLS)
    }

    @Test
    fun `PLAN_MODE_BLOCKED_TOOLS contains optimize_imports`() {
        assertTrue("optimize_imports" in SingleAgentSession.PLAN_MODE_BLOCKED_TOOLS)
    }

    @Test
    fun `PLAN_MODE_BLOCKED_TOOLS contains refactor_rename`() {
        assertTrue("refactor_rename" in SingleAgentSession.PLAN_MODE_BLOCKED_TOOLS)
    }

    @Test
    fun `PLAN_MODE_BLOCKED_TOOLS contains rollback_changes`() {
        assertTrue("rollback_changes" in SingleAgentSession.PLAN_MODE_BLOCKED_TOOLS)
    }

    @Test
    fun `PLAN_MODE_BLOCKED_TOOLS contains exactly 6 tools`() {
        assertEquals(6, SingleAgentSession.PLAN_MODE_BLOCKED_TOOLS.size)
    }

    // --- PLAN_MODE_BLOCKED_TOOLS does NOT contain read/analysis tools ---

    @Test
    fun `PLAN_MODE_BLOCKED_TOOLS does not contain read_file`() {
        assertFalse("read_file" in SingleAgentSession.PLAN_MODE_BLOCKED_TOOLS)
    }

    @Test
    fun `PLAN_MODE_BLOCKED_TOOLS does not contain search_code`() {
        assertFalse("search_code" in SingleAgentSession.PLAN_MODE_BLOCKED_TOOLS)
    }

    @Test
    fun `PLAN_MODE_BLOCKED_TOOLS does not contain diagnostics`() {
        assertFalse("diagnostics" in SingleAgentSession.PLAN_MODE_BLOCKED_TOOLS)
    }

    @Test
    fun `PLAN_MODE_BLOCKED_TOOLS does not contain run_command`() {
        assertFalse("run_command" in SingleAgentSession.PLAN_MODE_BLOCKED_TOOLS)
    }

    @Test
    fun `PLAN_MODE_BLOCKED_TOOLS does not contain think`() {
        assertFalse("think" in SingleAgentSession.PLAN_MODE_BLOCKED_TOOLS)
    }

    @Test
    fun `PLAN_MODE_BLOCKED_TOOLS does not contain create_plan`() {
        assertFalse("create_plan" in SingleAgentSession.PLAN_MODE_BLOCKED_TOOLS)
    }

    @Test
    fun `PLAN_MODE_BLOCKED_TOOLS does not contain enable_plan_mode`() {
        assertFalse("enable_plan_mode" in SingleAgentSession.PLAN_MODE_BLOCKED_TOOLS)
    }

    @Test
    fun `PLAN_MODE_BLOCKED_TOOLS does not contain attempt_completion`() {
        assertFalse("attempt_completion" in SingleAgentSession.PLAN_MODE_BLOCKED_TOOLS)
    }

    // --- PLAN_MODE_BLOCKED_TOOLS does NOT contain runtime or debug ---

    @Test
    fun `PLAN_MODE_BLOCKED_TOOLS does not contain runtime_config`() {
        assertFalse("runtime_config" in SingleAgentSession.PLAN_MODE_BLOCKED_TOOLS)
    }

    @Test
    fun `PLAN_MODE_BLOCKED_TOOLS does not contain runtime_exec`() {
        assertFalse("runtime_exec" in SingleAgentSession.PLAN_MODE_BLOCKED_TOOLS)
    }

    @Test
    fun `PLAN_MODE_BLOCKED_TOOLS does not contain debug_breakpoints`() {
        assertFalse("debug_breakpoints" in SingleAgentSession.PLAN_MODE_BLOCKED_TOOLS)
    }

    @Test
    fun `PLAN_MODE_BLOCKED_TOOLS does not contain debug_step`() {
        assertFalse("debug_step" in SingleAgentSession.PLAN_MODE_BLOCKED_TOOLS)
    }

    @Test
    fun `PLAN_MODE_BLOCKED_TOOLS does not contain debug_inspect`() {
        assertFalse("debug_inspect" in SingleAgentSession.PLAN_MODE_BLOCKED_TOOLS)
    }

    // --- filterToolsForPlanMode correctly filters tool map ---

    @Test
    fun `filterToolsForPlanMode removes blocked tools`() {
        val tools = mapOf(
            "read_file" to mockk<AgentTool>(),
            "edit_file" to mockk<AgentTool>(),
            "create_file" to mockk<AgentTool>(),
            "search_code" to mockk<AgentTool>(),
            "format_code" to mockk<AgentTool>(),
            "think" to mockk<AgentTool>()
        )

        val filtered = SingleAgentSession.filterToolsForPlanMode(tools)

        assertEquals(3, filtered.size)
        assertTrue("read_file" in filtered)
        assertTrue("search_code" in filtered)
        assertTrue("think" in filtered)
        assertFalse("edit_file" in filtered)
        assertFalse("create_file" in filtered)
        assertFalse("format_code" in filtered)
    }

    @Test
    fun `filterToolsForPlanMode keeps all tools when none are blocked`() {
        val tools = mapOf(
            "read_file" to mockk<AgentTool>(),
            "search_code" to mockk<AgentTool>(),
            "diagnostics" to mockk<AgentTool>()
        )

        val filtered = SingleAgentSession.filterToolsForPlanMode(tools)

        assertEquals(3, filtered.size)
    }

    @Test
    fun `filterToolsForPlanMode returns empty map when all tools are blocked`() {
        val tools = mapOf(
            "edit_file" to mockk<AgentTool>(),
            "create_file" to mockk<AgentTool>(),
            "rollback_changes" to mockk<AgentTool>()
        )

        val filtered = SingleAgentSession.filterToolsForPlanMode(tools)

        assertTrue(filtered.isEmpty())
    }

    // --- filterToolDefsForPlanMode correctly filters tool definitions ---

    private fun toolDef(name: String) = ToolDefinition(
        function = FunctionDefinition(
            name = name,
            description = "Test tool $name",
            parameters = FunctionParameters(properties = emptyMap())
        )
    )

    @Test
    fun `filterToolDefsForPlanMode removes blocked tool definitions`() {
        val toolDefs = listOf(
            toolDef("read_file"),
            toolDef("edit_file"),
            toolDef("create_file"),
            toolDef("search_code"),
            toolDef("optimize_imports"),
            toolDef("refactor_rename")
        )

        val filtered = SingleAgentSession.filterToolDefsForPlanMode(toolDefs)

        assertEquals(2, filtered.size)
        assertTrue(filtered.any { it.function.name == "read_file" })
        assertTrue(filtered.any { it.function.name == "search_code" })
        assertFalse(filtered.any { it.function.name == "edit_file" })
        assertFalse(filtered.any { it.function.name == "create_file" })
        assertFalse(filtered.any { it.function.name == "optimize_imports" })
        assertFalse(filtered.any { it.function.name == "refactor_rename" })
    }

    @Test
    fun `filterToolDefsForPlanMode keeps all defs when none are blocked`() {
        val toolDefs = listOf(
            toolDef("read_file"),
            toolDef("think"),
            toolDef("create_plan")
        )

        val filtered = SingleAgentSession.filterToolDefsForPlanMode(toolDefs)

        assertEquals(3, filtered.size)
    }

    @Test
    fun `filterToolDefsForPlanMode returns empty list when all are blocked`() {
        val toolDefs = listOf(
            toolDef("edit_file"),
            toolDef("create_file"),
            toolDef("format_code"),
            toolDef("optimize_imports"),
            toolDef("refactor_rename"),
            toolDef("rollback_changes")
        )

        val filtered = SingleAgentSession.filterToolDefsForPlanMode(toolDefs)

        assertTrue(filtered.isEmpty())
    }
}

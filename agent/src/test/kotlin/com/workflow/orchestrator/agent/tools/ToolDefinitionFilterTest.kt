package com.workflow.orchestrator.agent.tools

import com.workflow.orchestrator.agent.loop.AgentLoop
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Behavioral characterization of [ToolDefinitionFilter] — the plan-mode / act-mode / delegated /
 * skill tool-visibility predicate extracted out of `AgentService.executeTask`'s
 * `toolDefinitionProvider` closure (Phase 3 cut B, incision 3). It lived inline in the
 * ~1090-line god-function, so the only way to test it before was to re-implement the predicate in
 * a test (`AgentServiceToolFilterTest.filterForMode`) or grep the source text
 * (`DelegatedActOnlyToolFilterTest`). Carving it into a pure function makes the real logic
 * directly assertable; both of those tests now delegate here.
 *
 * Rules (preserved exactly from the inline filter):
 *  - `use_skill` is dropped when no skills are available;
 *  - a delegated session is ACT-ONLY → both plan tools (`enable_plan_mode` + `plan_mode_respond`)
 *    are dropped regardless of plan-mode state;
 *  - plan mode → drop write tools + `enable_plan_mode`, keep `plan_mode_respond`;
 *  - act mode → drop `plan_mode_respond` + `discard_plan`, keep everything else.
 */
class ToolDefinitionFilterTest {

    private val writeTools = AgentLoop.WRITE_TOOLS

    private fun include(
        toolName: String,
        isPlanMode: Boolean = false,
        isDelegatedSession: Boolean = false,
        hasSkills: Boolean = true,
    ) = ToolDefinitionFilter.shouldInclude(
        toolName = toolName,
        isPlanMode = isPlanMode,
        isDelegatedSession = isDelegatedSession,
        hasSkills = hasSkills,
        writeToolNames = writeTools,
    )

    @Test
    fun `use_skill is dropped when no skills are available and kept otherwise`() {
        assertFalse(include("use_skill", hasSkills = false))
        assertTrue(include("use_skill", hasSkills = true))
    }

    @Test
    fun `delegated session drops both plan tools regardless of plan mode`() {
        assertFalse(include("enable_plan_mode", isDelegatedSession = true, isPlanMode = false))
        assertFalse(include("plan_mode_respond", isDelegatedSession = true, isPlanMode = true))
        // a normal read tool is still allowed on a delegated (act-only) session
        assertTrue(include("read_file", isDelegatedSession = true))
    }

    @Test
    fun `plan mode drops write tools and enable_plan_mode but keeps plan_mode_respond`() {
        val aWriteTool = writeTools.first()
        assertFalse(include(aWriteTool, isPlanMode = true))
        assertFalse(include("enable_plan_mode", isPlanMode = true))
        assertTrue(include("plan_mode_respond", isPlanMode = true))
        assertTrue(include("read_file", isPlanMode = true))
    }

    @Test
    fun `act mode drops plan_mode_respond and discard_plan but keeps write tools and enable_plan_mode`() {
        assertFalse(include("plan_mode_respond", isPlanMode = false))
        assertFalse(include("discard_plan", isPlanMode = false))
        assertTrue(include(writeTools.first(), isPlanMode = false))
        assertTrue(include("enable_plan_mode", isPlanMode = false))
    }
}

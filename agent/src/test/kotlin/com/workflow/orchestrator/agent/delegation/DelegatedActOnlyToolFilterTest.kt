package com.workflow.orchestrator.agent.delegation

import com.workflow.orchestrator.agent.loop.AgentLoop
import com.workflow.orchestrator.agent.tools.ToolDefinitionFilter
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * #5 reconciliation pin — a delegated session is ACT-ONLY.
 *
 * Rather than wire an interactive plan-approval surface over a remotely-driven delegated session
 * (there is no local human to approve a plan), the tool-definition filter excludes the plan tools
 * (`enable_plan_mode` + `plan_mode_respond`) from a delegated session's tool set — mirroring how
 * sub-agents are act-only (`includePlanModeSection = false`). So the plan callbacks in
 * `SessionUiCallbacks` are simply never exercised on the delegated path.
 *
 * The filter logic now lives in the pure [ToolDefinitionFilter] (Phase 3 cut B, incision 3),
 * extracted from `AgentService.executeTask`'s `toolDefinitionProvider` closure. `AgentService`
 * computes `isDelegatedSession = currentSessionState()?.delegated != null` and passes it in. This
 * test pins the real predicate behaviorally (was a source-text grep on AgentService.kt before the
 * extraction made the logic reachable).
 */
class DelegatedActOnlyToolFilterTest {

    private fun include(toolName: String, isDelegatedSession: Boolean, isPlanMode: Boolean = false) =
        ToolDefinitionFilter.shouldInclude(
            toolName = toolName,
            isPlanMode = isPlanMode,
            isDelegatedSession = isDelegatedSession,
            hasSkills = true,
            writeToolNames = AgentLoop.WRITE_TOOLS,
        )

    @Test
    fun `delegated act-only session excludes both plan tools regardless of plan mode`() {
        // Act mode already drops plan_mode_respond; the delegated branch must ADDITIONALLY drop
        // enable_plan_mode so the LLM can never switch a delegated session into plan mode.
        assertFalse(include("enable_plan_mode", isDelegatedSession = true, isPlanMode = false))
        assertFalse(include("plan_mode_respond", isDelegatedSession = true, isPlanMode = false))
        // ...and even if the per-session plan-mode flag were somehow set, both stay dropped.
        assertFalse(include("enable_plan_mode", isDelegatedSession = true, isPlanMode = true))
        assertFalse(include("plan_mode_respond", isDelegatedSession = true, isPlanMode = true))
    }

    @Test
    fun `a non-delegated session keeps enable_plan_mode in act mode`() {
        // Guards against the filter over-dropping: only the delegated path removes enable_plan_mode.
        assertTrue(include("enable_plan_mode", isDelegatedSession = false, isPlanMode = false))
    }

    @Test
    fun `delegated session still allows ordinary act-mode tools`() {
        assertTrue(include("read_file", isDelegatedSession = true))
        assertTrue(include("edit_file", isDelegatedSession = true))
    }
}

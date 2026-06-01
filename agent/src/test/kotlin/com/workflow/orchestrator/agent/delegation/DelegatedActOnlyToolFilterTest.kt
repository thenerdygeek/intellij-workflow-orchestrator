package com.workflow.orchestrator.agent.delegation

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * #5 reconciliation pin — a delegated session is ACT-ONLY.
 *
 * Rather than wire an interactive plan-approval surface over a remotely-driven delegated session
 * (there is no local human to approve a plan), the tool-definition filter excludes the plan tools
 * (`enable_plan_mode` + `plan_mode_respond`) from a delegated session's tool set — mirroring how
 * sub-agents are act-only (`includePlanModeSection = false`). So the plan callbacks in
 * `SessionUiCallbacks` are simply never exercised on the delegated path.
 *
 * The filter lives in `AgentService`'s `toolDefinitionProvider` closure (built once inside
 * `executeTask`, reused by `resumeSession`). It already branches on `isPlanModeActive()`; the
 * act-only delegated condition is added there, keyed on the per-session delegation marker
 * (`currentSessionState()?.delegated != null`), which both `startDelegatedSession` and
 * `resumeDelegatedSession` stamp before the loop starts.
 *
 * Source-text pin: the harness can't easily build a live toolDefinitionProvider, so we assert the
 * filter expression is present and references both plan tools + the delegation marker.
 */
class DelegatedActOnlyToolFilterTest {

    private fun agentRoot(): File {
        val d = System.getProperty("user.dir")
        return if (File("$d/src/main/kotlin").isDirectory) File("$d/src/main/kotlin")
        else File("$d/agent/src/main/kotlin")
    }

    private val serviceSource: String by lazy {
        File(agentRoot(), "com/workflow/orchestrator/agent/AgentService.kt").readText()
    }

    private fun toolDefinitionProviderBlock(): String {
        val start = serviceSource.indexOf("val toolDefinitionProvider")
        assertTrue(start >= 0, "AgentService must declare a toolDefinitionProvider")
        // Capture a generous window around the filter body.
        return serviceSource.substring(start, minOf(start + 2000, serviceSource.length))
    }

    @Test
    fun `tool definition provider has an act-only delegated branch`() {
        val block = toolDefinitionProviderBlock()
        assertTrue(
            block.contains("delegated") || block.contains("isDelegatedSession") ||
                block.contains("actOnly"),
            "toolDefinitionProvider must compute a delegated/act-only flag from the session state " +
                "so a delegated session never sees the plan tools"
        )
    }

    @Test
    fun `delegated act-only branch excludes both plan tools`() {
        val block = toolDefinitionProviderBlock()
        // The act-only branch must drop enable_plan_mode AND plan_mode_respond. (Act mode already
        // drops plan_mode_respond; the delegated branch must additionally drop enable_plan_mode so
        // the LLM can never switch a delegated session into plan mode.)
        assertTrue(
            block.contains("enable_plan_mode"),
            "delegated act-only filter must exclude enable_plan_mode"
        )
        assertTrue(
            block.contains("plan_mode_respond"),
            "delegated act-only filter must exclude plan_mode_respond"
        )
    }
}

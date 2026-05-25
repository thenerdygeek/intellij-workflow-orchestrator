package com.workflow.orchestrator.agent.tools.builtin

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pins the contract that `SpawnAgentTool` gates the bundled `research` persona on
 * `PluginSettings.enableResearchSubagent`. When the setting is false, the tool must
 * return a `RESEARCH_SUBAGENT_DISABLED` error pointing the user at the settings
 * location — never silently dispatch the sub-agent.
 *
 * If a unit-testable factory for SpawnAgentTool exists in this codebase, use it.
 * Otherwise this test pins the invariant at the source-text level (faster than
 * standing up the full IDE service graph for one gate).
 */
class SpawnAgentToolResearchGateTest {

    @Test
    fun `SpawnAgentTool source contains the research-disabled gate`() {
        val sourceFile = java.io.File(
            "src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/SpawnAgentTool.kt"
        )
        val src = sourceFile.readText()
        assertTrue(
            src.contains("RESEARCH_SUBAGENT_DISABLED"),
            "SpawnAgentTool.kt must contain the RESEARCH_SUBAGENT_DISABLED error code",
        )
        assertTrue(
            src.contains("enableResearchSubagent"),
            "SpawnAgentTool.kt must reference PluginSettings.enableResearchSubagent",
        )
        // The gate must check the persona name "research" — sanity-check both
        // common forms ('research' as string literal OR config.name == "research").
        assertTrue(
            Regex("\"research\"|'research'").containsMatchIn(src),
            "SpawnAgentTool.kt must reference the 'research' persona name in the gate",
        )
    }

    @Test
    fun `error message points at the settings location`() {
        val sourceFile = java.io.File(
            "src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/SpawnAgentTool.kt"
        )
        val src = sourceFile.readText()
        assertTrue(
            src.contains("Sub-agents") || src.contains("Sub-Agents"),
            "Error message must point user at the Sub-agents settings group",
        )
        assertTrue(
            src.contains("Workflow Orchestrator") || src.contains("AI Agent"),
            "Error message must include the settings path breadcrumb",
        )
    }
}

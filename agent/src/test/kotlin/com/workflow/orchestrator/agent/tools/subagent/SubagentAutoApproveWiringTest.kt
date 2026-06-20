package com.workflow.orchestrator.agent.tools.subagent

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class SubagentAutoApproveWiringTest {
    @Test fun `SubagentRunner forwards allowlist + toggle into its AgentLoop`() {
        val src = File("src/main/kotlin/com/workflow/orchestrator/agent/tools/subagent/SubagentRunner.kt").readText()
        assertTrue(src.contains("sessionCommandAllowlist"), "SubagentRunner must accept + forward sessionCommandAllowlist")
        assertTrue(src.contains("autoApproveSafeCommands"), "SubagentRunner must accept + forward autoApproveSafeCommands")
    }

    @Test fun `SpawnAgentTool forwards allowlist + toggle to SubagentRunner`() {
        val src = File("src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/SpawnAgentTool.kt").readText()
        assertTrue(src.contains("sessionCommandAllowlist"))
        assertTrue(src.contains("autoApproveSafeCommands"))
    }
}

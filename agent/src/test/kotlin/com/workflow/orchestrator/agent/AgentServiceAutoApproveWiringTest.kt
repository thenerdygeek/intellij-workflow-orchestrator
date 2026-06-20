package com.workflow.orchestrator.agent

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class AgentServiceAutoApproveWiringTest {
    private val src = File("src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt").readText()

    @Test fun `autoApproveSafeCommands passed to AgentLoop at least twice (executeTask + resumeSession)`() {
        val count = Regex("autoApproveSafeCommands\\s*=\\s*agentSettings\\.state\\.autoApproveSafeCommands")
            .findAll(src).count()
        assertTrue(count >= 2, "expected wiring at both executeTask and resumeSession loop builds, found $count")
    }

    @Test fun `sessionCommandAllowlist threaded to AgentLoop at least twice`() {
        val count = Regex("sessionCommandAllowlist\\s*=\\s*sessionCommandAllowlist").findAll(src).count()
        assertTrue(count >= 2, "expected sessionCommandAllowlist wired at both loop builds, found $count")
    }

    @Test fun `spawnAgentTool receives the allowlist`() {
        assertTrue(src.contains("spawnAgentTool.sessionCommandAllowlist"))
        assertTrue(src.contains("spawnAgentTool.autoApproveSafeCommands"))
    }
}

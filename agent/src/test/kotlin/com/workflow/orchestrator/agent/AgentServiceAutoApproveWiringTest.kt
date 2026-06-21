package com.workflow.orchestrator.agent

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class AgentServiceAutoApproveWiringTest {
    private val src = File("src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt").readText()

    @Test fun `autoApproveSafeCommands wired at the loop build plus resume delegation`() {
        val count = Regex("autoApproveSafeCommands\\s*=\\s*agentSettings\\.state\\.autoApproveSafeCommands")
            .findAll(src).count()
        assertTrue(count >= 2, "expected wiring at the loop build and via resume delegation, found $count")
    }

    @Test fun `sessionCommandAllowlist threaded at the loop build plus resume delegation`() {
        val count = Regex("sessionCommandAllowlist\\s*=\\s*sessionCommandAllowlist").findAll(src).count()
        assertTrue(
            count >= 2,
            "expected sessionCommandAllowlist wired at the loop build and via resume delegation, found $count",
        )
    }

    @Test fun `spawnAgentTool receives the allowlist`() {
        assertTrue(src.contains("spawnAgentTool.sessionCommandAllowlist"))
        assertTrue(src.contains("spawnAgentTool.autoApproveSafeCommands"))
    }
}

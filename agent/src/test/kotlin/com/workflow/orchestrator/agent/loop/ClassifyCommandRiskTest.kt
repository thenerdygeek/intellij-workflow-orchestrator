package com.workflow.orchestrator.agent.loop

import com.workflow.orchestrator.agent.security.CommandRisk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ClassifyCommandRiskTest {
    @Test fun `maps analyzer verdicts`() {
        assertEquals(CommandRisk.SAFE, AgentLoop.classifyCommandRisk("ls -la"))
        assertEquals(CommandRisk.RISKY, AgentLoop.classifyCommandRisk("git push"))
        assertEquals(CommandRisk.DANGEROUS, AgentLoop.classifyCommandRisk("rm -rf /"))
    }
}

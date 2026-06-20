package com.workflow.orchestrator.agent.security

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CommandApprovalDecisionTest {
    private fun eval(cmd: String, risk: CommandRisk, safe: Boolean, allow: Set<String> = emptySet()) =
        CommandApprovalDecision.evaluate(cmd, risk, safe, allow)

    @Test fun `blank and dangerous always prompt`() {
        assertEquals(ApprovalDecision.Prompt, eval("", CommandRisk.SAFE, true))
        assertEquals(ApprovalDecision.Prompt, eval("rm -rf /", CommandRisk.DANGEROUS, true, setOf("rm")))
    }

    @Test fun `not structurally simple prompts even when SAFE and toggle on`() {
        assertEquals(ApprovalDecision.Prompt, eval("git status > out", CommandRisk.SAFE, true))
    }

    @Test fun `part A skips SAFE only when toggle on`() {
        assertEquals(ApprovalDecision.Skip(AutoApproveReason.Safe), eval("ls -la", CommandRisk.SAFE, true))
        assertEquals(ApprovalDecision.Prompt, eval("ls -la", CommandRisk.SAFE, false))
    }

    @Test fun `part B skips RISKY when covered by a session prefix`() {
        val d = eval("git pull origin", CommandRisk.RISKY, false, setOf("git pull"))
        assertTrue(d is ApprovalDecision.Skip && d.reason == AutoApproveReason.SessionRule(listOf("git pull")))
        assertEquals(ApprovalDecision.Prompt, eval("git pull origin", CommandRisk.RISKY, false, setOf("git add")))
    }
}

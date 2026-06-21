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

    // ── T2: RISKY + toggle ON + covered → SessionRule (Part B regardless of risk) ──
    @Test fun `T2 RISKY with toggle ON and covered by session prefix skips with SessionRule reason`() {
        // Part B fires after Part A check; RISKY is not SAFE so Part A does not skip,
        // but Part B does because the prefix covers it.
        val d = eval("git pull origin", CommandRisk.RISKY, true, setOf("git pull"))
        assertTrue(d is ApprovalDecision.Skip)
        val reason = (d as ApprovalDecision.Skip).reason
        assertTrue(reason is AutoApproveReason.SessionRule)
        assertEquals(listOf("git pull"), (reason as AutoApproveReason.SessionRule).prefixes)
    }

    // ── Part A vs Part B precedence for SAFE + covered ──
    @Test fun `SAFE with toggle ON and also covered by prefix skips with Safe reason not SessionRule`() {
        // Part A runs first; SAFE + toggle ON → Skip(Safe) before Part B is even checked.
        val d = eval("ls -la", CommandRisk.SAFE, true, setOf("ls"))
        assertEquals(ApprovalDecision.Skip(AutoApproveReason.Safe), d)
    }

    // ── DANGEROUS: never skipped, even when covered by a prefix ──
    @Test fun `DANGEROUS command covered by a prefix still prompts`() {
        assertEquals(ApprovalDecision.Prompt, eval("rm -rf /", CommandRisk.DANGEROUS, true, setOf("rm")))
    }

    @Test fun `DANGEROUS command with toggle ON and matching prefix still prompts`() {
        assertEquals(ApprovalDecision.Prompt, eval("sudo rm -rf /", CommandRisk.DANGEROUS, true, setOf("sudo")))
    }

    // ── Not structurally simple: prompts regardless of toggle or prefix ──
    @Test fun `not structurally simple with matching prefix still prompts`() {
        // redirect makes it not simple → coveringPrefixes returns null → Prompt
        val d = eval("git add . > out", CommandRisk.SAFE, true, setOf("git add"))
        assertEquals(ApprovalDecision.Prompt, d)
    }

    @Test fun `not structurally simple with RISKY risk and matching prefix still prompts`() {
        val d = eval("git status > out.txt", CommandRisk.RISKY, false, setOf("git status"))
        assertEquals(ApprovalDecision.Prompt, d)
    }

    // ── Multiple prefixes cover a compound command ──
    @Test fun `compound command with both sides covered lists both matched prefixes in SessionRule`() {
        val allow = setOf("git add", "git status")
        val d = eval("git add . && git status", CommandRisk.RISKY, false, allow)
        assertTrue(d is ApprovalDecision.Skip)
        val reason = (d as ApprovalDecision.Skip).reason
        assertTrue(reason is AutoApproveReason.SessionRule)
        val prefixes = (reason as AutoApproveReason.SessionRule).prefixes
        // Both distinct prefixes appear (order determined by sub-command order)
        assertEquals(listOf("git add", "git status"), prefixes)
    }

    @Test fun `compound command where both sides share the same prefix lists that prefix once`() {
        // "git add a && git add b" — both sides match "git add" → distinct → one entry
        val allow = setOf("git add")
        val d = eval("git add a && git add b", CommandRisk.RISKY, false, allow)
        assertTrue(d is ApprovalDecision.Skip)
        val reason = (d as ApprovalDecision.Skip).reason as AutoApproveReason.SessionRule
        assertEquals(listOf("git add"), reason.prefixes)
    }

    // ── Toggle OFF: safe command with empty allowlist → Prompt ──
    @Test fun `toggle OFF with SAFE risk and empty allowlist prompts`() {
        assertEquals(ApprovalDecision.Prompt, eval("ls -la", CommandRisk.SAFE, false, emptySet()))
    }

    // ── Blank edge cases ──
    @Test fun `whitespace-only command prompts`() {
        assertEquals(ApprovalDecision.Prompt, eval("   ", CommandRisk.SAFE, true))
    }
}

package com.workflow.orchestrator.agent.ui

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Source-text contract for the run_command prefix-approval + auto-approve badge wiring in
 * [AgentController]. `AgentController` is not unit-instantiable (it owns a live JCEF panel and
 * resolves project services), so — per module convention for UI classes — we pin the wiring by
 * asserting on the source text.
 *
 * Locks in Task 9:
 *  - the controller OWNS and CLEARS a [com.workflow.orchestrator.agent.loop.SessionCommandAllowlist]
 *  - the run_command approval card DERIVES a prefix via [com.workflow.orchestrator.agent.security.CommandShape.derivePrefix]
 *  - the prefix-approval callback APPROVES the prefix then completes APPROVED
 *  - the auto-approve badge is FORWARDED from [com.workflow.orchestrator.agent.loop.ToolCallProgress]
 *    into appendToolCall
 */
class AgentControllerPrefixApprovalWiringTest {
    private val src = File("src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt").readText()

    @Test
    fun `owns and clears a SessionCommandAllowlist`() {
        assertTrue(
            src.contains("SessionCommandAllowlist()"),
            "AgentController must construct a SessionCommandAllowlist",
        )
        assertTrue(
            Regex("sessionCommandAllowlist\\.clear\\(\\)").containsMatchIn(src),
            "resetForNewChat must clear the sessionCommandAllowlist",
        )
    }

    @Test
    fun `derives a prefix for the run_command approval card`() {
        assertTrue(
            src.contains("CommandShape.derivePrefix"),
            "approvalGate must derive the button-label prefix via CommandShape.derivePrefix",
        )
    }

    @Test
    fun `prefix approval approves the prefix then completes APPROVED`() {
        assertTrue(
            src.contains("sessionCommandAllowlist.approve"),
            "the prefix-approval callback must approve the prefix into the allowlist",
        )
        assertTrue(
            Regex("onApproveCommandPrefix").containsMatchIn(src),
            "the panel approval callbacks must wire onApproveCommandPrefix",
        )
    }

    @Test
    fun `forwards auto-approve badge into appendToolCall`() {
        assertTrue(src.contains("progress.autoApproved"), "onToolCall must forward progress.autoApproved")
        assertTrue(src.contains("progress.autoApproveReason"), "onToolCall must forward progress.autoApproveReason")
    }
}

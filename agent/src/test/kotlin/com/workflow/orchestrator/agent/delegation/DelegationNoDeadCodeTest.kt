package com.workflow.orchestrator.agent.delegation

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/**
 * Requirement 5 guard (spec 2026-05-28 IDE-B agent reuse): the old UI-stripped headless
 * delegated-execution path must NOT survive alongside the controller-driven path. A delegated
 * session is run only through `AgentController` (which wires the full agent: tools, IDE-B approval
 * gate, streaming). This pins that the headless invocation and its busy-case shortcut are gone.
 */
class DelegationNoDeadCodeTest {
    private fun mainRoot(): String {
        val d = System.getProperty("user.dir")
        return if (java.io.File("$d/src/main/kotlin").isDirectory) "$d/src/main/kotlin" else "$d/agent/src/main/kotlin"
    }
    private fun src(rel: String): String = java.io.File(mainRoot(), rel).readText()

    @Test fun `inbound never invokes the headless agentService startDelegatedSession`() {
        val s = src("com/workflow/orchestrator/agent/delegation/DelegationInboundService.kt")
        assertFalse(
            Regex("""agentService\.startDelegatedSession\(""").containsMatchIn(s),
            "DelegationInboundService must start delegated sessions via AgentController, not the headless AgentService path",
        )
    }

    @Test fun `the old fake-FAILED busy shortcut is gone (replaced by the top-bar accept window)`() {
        val s = src("com/workflow/orchestrator/agent/delegation/DelegationInboundService.kt")
        // Scope to handleConnect: that's where the old fake-FAILED busy shortcut lived (it replied a
        // synthetic DelegationMessage.Result(FAILED, reason="ide_b_busy...") that bypassed the top-bar
        // accept window). The busy case must instead surface the top-bar incoming-delegation button and,
        // on expiry, reply AcceptResult(accepted=false) with a self-describing ide_b_busy reason composed
        // by composeBusyDeclineReason (PART 2). Pin that the FAILED shortcut is gone WITHOUT forbidding
        // the new ide_b_busy reason wording, which legitimately lives on the AcceptResult decline.
        val handleConnectBody = s.substringAfter("private suspend fun handleConnect(")
            .substringBefore("internal suspend fun runInboundReadLoop(")
        assertFalse(
            Regex("""ResultStatus\.FAILED[\s\S]{0,120}ide_b_busy""").containsMatchIn(handleConnectBody),
            "the busy case must surface the top-bar incoming-delegation button (AcceptResult decline on expiry), not reply a fake FAILED Result",
        )
    }
}

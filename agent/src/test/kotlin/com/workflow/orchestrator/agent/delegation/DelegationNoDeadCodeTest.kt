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

    @Test fun `the old ide_b_busy FAILED shortcut is gone (replaced by the top-bar accept window)`() {
        val s = src("com/workflow/orchestrator/agent/delegation/DelegationInboundService.kt")
        // Scope to handleConnect: that's where the old fake-FAILED busy shortcut lived. Fix 3's
        // handleChannelResume legitimately uses an ide_b_busy SessionClosed reason for the
        // resurrection busy-decline (a distinct path), so a whole-file match would over-trigger.
        val handleConnectBody = s.substringAfter("private suspend fun handleConnect(")
            .substringBefore("internal suspend fun runInboundReadLoop(")
        assertFalse(
            handleConnectBody.contains("ide_b_busy"),
            "the busy case must surface the top-bar incoming-delegation button (declined_timeout on expiry), not reply a fake FAILED",
        )
    }
}

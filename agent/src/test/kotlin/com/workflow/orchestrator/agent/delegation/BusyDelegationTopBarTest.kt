package com.workflow.orchestrator.agent.delegation

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Source-contract test for the busy-case "incoming delegation" top-bar accept window
 * (Plan 2 Task 4 follow-up). A full behavioral coroutine-timing test is impractical
 * headless (no live Application/EDT, real `withTimeoutOrNull` clock), so we pin the
 * load-bearing wiring at the source level — the same pattern used by
 * `DelegationInboundRoutingTest` and the other delegation source-contract tests.
 */
class BusyDelegationTopBarTest {
    private fun src(rel: String): String {
        val d = System.getProperty("user.dir")
        val root = if (File("$d/src/main/kotlin").isDirectory) "$d/src/main/kotlin" else "$d/agent/src/main/kotlin"
        return File(root, rel).readText()
    }

    private fun controller() =
        src("com/workflow/orchestrator/agent/ui/AgentController.kt")

    private fun inbound() =
        src("com/workflow/orchestrator/agent/delegation/DelegationInboundService.kt")

    private fun cefPanel() =
        src("com/workflow/orchestrator/agent/ui/AgentCefPanel.kt")

    @Test
    fun `controller exposes startIncomingDelegation and a bounded busy accept window`() {
        val s = controller()
        assertTrue(
            s.contains("fun startIncomingDelegation("),
            "AgentController must expose startIncomingDelegation(key) for the Start signal",
        )
        assertTrue(
            s.contains("withTimeoutOrNull("),
            "the busy branch must bound the human accept window with withTimeoutOrNull",
        )
        assertTrue(
            s.contains("ACCEPT_WINDOW_MS"),
            "the busy accept window must use the ACCEPT_WINDOW_MS constant",
        )
        // Pushes the incoming-delegation state + a clear to the webview.
        assertTrue(
            s.contains("_incomingDelegation"),
            "controller must push the _incomingDelegation state to the webview",
        )
        assertTrue(
            s.contains("_incomingDelegationCleared"),
            "controller must push the _incomingDelegationCleared signal when the window ends",
        )
    }

    @Test
    fun `controller startDelegatedSession reports a typed busy-or-started outcome`() {
        val s = controller()
        assertTrue(
            s.contains("DelegatedStartOutcome"),
            "startDelegatedSession must report a typed outcome (STARTED / DECLINED_TIMEOUT)",
        )
        assertTrue(
            Regex("""enum class DelegatedStartOutcome""").containsMatchIn(s),
            "DelegatedStartOutcome enum must be declared",
        )
        assertTrue(
            s.contains("STARTED") && s.contains("DECLINED_TIMEOUT"),
            "outcome must distinguish STARTED from DECLINED_TIMEOUT",
        )
    }

    @Test
    fun `handleConnect handles the declined-timeout outcome and no longer replies ide_b_busy`() {
        val s = inbound()
        assertTrue(
            s.contains("DECLINED_TIMEOUT"),
            "handleConnect must branch on the DECLINED_TIMEOUT outcome",
        )
        assertTrue(
            s.contains("declined_timeout"),
            "handleConnect must reply AcceptResult(accepted=false, reason=declined_timeout) on timeout",
        )
        assertFalse(
            s.contains("ide_b_busy"),
            "the old ide_b_busy FAILED reply must be removed",
        )
    }

    @Test
    fun `cef panel wires the _startIncomingDelegation bridge`() {
        val s = cefPanel()
        assertTrue(
            s.contains("_startIncomingDelegation"),
            "AgentCefPanel must inject the _startIncomingDelegation JS bridge",
        )
        assertTrue(
            s.contains("onStartIncomingDelegation"),
            "AgentCefPanel must expose an onStartIncomingDelegation callback",
        )
    }
}

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
    fun `handleConnect handles the declined-timeout outcome with a self-describing ide_b_busy reason`() {
        val s = inbound()
        assertTrue(
            s.contains("DECLINED_TIMEOUT"),
            "handleConnect must branch on the DECLINED_TIMEOUT outcome",
        )
        // PART 2 — busy-enrichment: the fresh-connect decline now composes its reason FROM the
        // in-flight descriptor via the shared composer (a single coherent `ide_b_busy:` reason),
        // replacing the old bare `declined_timeout:` string.
        val handleConnectBody = s.substringAfter("private suspend fun handleConnect(")
            .substringBefore("internal suspend fun runInboundReadLoop(")
        assertTrue(
            handleConnectBody.contains("composeBusyDeclineReason"),
            "handleConnect must compose the decline reason from the BusyInfo descriptor",
        )
    }

    @Test
    fun `the busy decline reason names the in-flight task and the accept-window mechanism`() {
        // PART 2: the reason must lead with `ide_b_busy:`, name the in-flight session, and spell out
        // the real mechanism (busy tab → Start prompt → not clicked within ACCEPT_WINDOW_MS).
        val src = run {
            val d = System.getProperty("user.dir")
            val root = if (File("$d/src/main/kotlin").isDirectory) "$d/src/main/kotlin" else "$d/agent/src/main/kotlin"
            File(root, "com/workflow/orchestrator/agent/delegation/DelegationInboundService.kt").readText()
        }
        assertTrue(
            src.contains("ide_b_busy: agent tab is busy running"),
            "the composer must lead with ide_b_busy and name the in-flight session",
        )
        assertTrue(
            Regex("""did not accept the takeover""").containsMatchIn(src),
            "the reason must explain the user did not accept the takeover in time",
        )
        assertTrue(
            src.contains("ACCEPT_WINDOW_MS"),
            "the reason must use the real ACCEPT_WINDOW_MS, not a hardcoded value",
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

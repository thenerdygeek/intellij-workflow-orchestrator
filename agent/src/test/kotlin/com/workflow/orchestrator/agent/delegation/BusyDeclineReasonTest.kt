package com.workflow.orchestrator.agent.delegation

import com.workflow.orchestrator.agent.ui.AgentController
import com.workflow.orchestrator.agent.ui.BusyInfo
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * PART 2 — self-describing busy decline. Unit coverage for [composeBusyDeclineReason]: the reason
 * IDE-B sends when it declines an incoming delegation because its agent tab is busy must NAME the
 * in-flight session and — when that task is itself delegated — echo the delegator session id so
 * IDE-A can recognize the blocker as its OWN earlier task.
 */
class BusyDeclineReasonTest {

    private val windowSeconds = AgentController.ACCEPT_WINDOW_MS / 1000

    @Test
    fun `reason names the in-flight session and echoes the delegator session id when delegated`() {
        val info = BusyInfo(
            inFlightSessionId = "b-sess-1",
            inFlightTitle = "Fix the parser",
            inFlightDelegatorSessionId = "a-sess-1",
            inFlightDelegatorRepo = "backend",
        )
        val reason = composeBusyDeclineReason(info)
        assertTrue(reason.startsWith("ide_b_busy:"), reason)
        assertTrue(reason.contains("b-sess-1"), "must name the in-flight session id")
        assertTrue(reason.contains("Fix the parser"), "must name the in-flight title")
        // CRITICAL: echo the delegator session id of the in-flight task so IDE-A can match it.
        assertTrue(reason.contains("a-sess-1"), "must echo the in-flight delegator session id")
        assertTrue(reason.contains("backend"), "must name the delegator repo")
        // Message must convey a takeover prompt exists and a human must accept it.
        assertTrue(
            reason.contains("takeover") || reason.contains("accepted within"),
            "reason must mention takeover/accept window: $reason"
        )
        assertTrue(reason.contains("${windowSeconds}s"), "must use the real ACCEPT_WINDOW_MS, not a hardcoded value")
    }

    @Test
    fun `reason names the local session without a delegator clause when the in-flight task is not delegated`() {
        val info = BusyInfo(
            inFlightSessionId = "local-1",
            inFlightTitle = "Local refactor",
            inFlightDelegatorSessionId = null,
            inFlightDelegatorRepo = null,
        )
        val reason = composeBusyDeclineReason(info)
        assertTrue(reason.startsWith("ide_b_busy:"), reason)
        assertTrue(reason.contains("local-1"))
        assertTrue(reason.contains("Local refactor"))
        assertFalse(reason.contains("delegated by"), "a non-delegated in-flight task must omit the delegator clause")
    }

    @Test
    fun `reason falls back to the generic string when the descriptor is null`() {
        val reason = composeBusyDeclineReason(null)
        assertTrue(reason.startsWith("ide_b_busy:"), reason)
        // Generic fallback must mention takeover/accept window.
        assertTrue(
            reason.contains("takeover") || reason.contains("accepted within"),
            "generic fallback must mention takeover/accept window: $reason"
        )
        assertTrue(reason.contains("${windowSeconds}s"))
    }

    @Test
    fun `null fallback honors a caller-supplied generic fallback string`() {
        val reason = composeBusyDeclineReason(null, genericFallback = "ide_b_busy: could not resume")
        assertTrue(reason == "ide_b_busy: could not resume")
    }

    @Test
    fun `reason falls back to generic when the in-flight session id itself is unknown`() {
        val info = BusyInfo(
            inFlightSessionId = null,
            inFlightTitle = "title without an id",
            inFlightDelegatorSessionId = "a-sess",
            inFlightDelegatorRepo = "repo",
        )
        val reason = composeBusyDeclineReason(info)
        // Without a session id there is nothing concrete to name → generic fallback.
        assertTrue(reason.startsWith("ide_b_busy:"))
        assertFalse(reason.contains("a-sess"))
    }

    @Test
    fun `reason leads with the busy token and is a single coherent ide_b_busy reason`() {
        val info = BusyInfo("b-sess", "T", "a-sess", "backend")
        val reason = composeBusyDeclineReason(info)
        // Single token, not conflated with session_closed/declined_timeout.
        assertTrue(reason.startsWith("ide_b_busy:"))
        assertFalse(reason.contains("session_closed:"))
        assertFalse(reason.contains("declined_timeout:"))
    }

    // ── Part 2 — consent/takeover clarity and configured-N ─────────────────

    @Test
    fun `reason mentions a takeover or consent prompt waiting for user action`() {
        val info = BusyInfo("s1", "some task", null, null)
        val reason = composeBusyDeclineReason(info)
        // The message must make it clear a HUMAN must act on the target IDE.
        assertTrue(
            reason.contains("takeover") || reason.contains("consent"),
            "reason must mention 'takeover' or 'consent' so the delegating agent knows a human must act: $reason"
        )
    }

    @Test
    fun `reason does not contain the literal string IDE-B`() {
        val info = BusyInfo("s1", "task", "a-sess", "frontend")
        val reason = composeBusyDeclineReason(info)
        assertFalse(
            reason.contains("IDE-B", ignoreCase = false),
            "reason must not use the implementation-internal 'IDE-B' label; use repo-name or neutral wording: $reason"
        )
        // Generic fallback must also not say IDE-B
        val fallback = composeBusyDeclineReason(null)
        assertFalse(
            fallback.contains("IDE-B", ignoreCase = false),
            "generic fallback must not use 'IDE-B': $fallback"
        )
    }

    @Test
    fun `reason conveys that a human must accept the takeover on the target`() {
        // The message must communicate human agency — not just a timeout.
        val info = BusyInfo("s2", "fix tests", "a-sess", "analytics")
        val reason = composeBusyDeclineReason(info)
        // Must mention user/human or imply that a person needs to act.
        val humanSignals = listOf("user", "human", "accept", "takeover", "prompt")
        assertTrue(
            humanSignals.any { reason.contains(it, ignoreCase = true) },
            "reason must convey that a human must act on the target: $reason"
        )
    }

    @Test
    fun `reason uses configured window seconds not a hardcoded number`() {
        // composeBusyDeclineReason receives configuredWindowSeconds explicitly.
        // Verify overriding the window yields N in the output (not a constant 55).
        val info = BusyInfo("s3", "build pipeline", null, null)
        val reason30 = composeBusyDeclineReason(info, configuredWindowSeconds = 30)
        val reason90 = composeBusyDeclineReason(info, configuredWindowSeconds = 90)
        assertTrue(reason30.contains("30s"), "reason must reflect configured window (30s): $reason30")
        assertTrue(reason90.contains("90s"), "reason must reflect configured window (90s): $reason90")
        // The old hardcoded 55 must not appear in a 30s-configured call
        assertFalse(reason30.contains("55s"), "must not contain hardcoded 55 when configured to 30s: $reason30")
    }

    @Test
    fun `generic fallback also uses configured window seconds`() {
        val fallback30 = composeBusyDeclineReason(null, configuredWindowSeconds = 30)
        assertTrue(fallback30.startsWith("ide_b_busy:"))
        assertTrue(fallback30.contains("30s"), "generic fallback must reflect configured window (30s): $fallback30")
    }

    @Test
    fun `default configuredWindowSeconds matches ACCEPT_WINDOW_MS`() {
        // When called without an explicit configuredWindowSeconds, the default must
        // equal AgentController.ACCEPT_WINDOW_MS / 1000 so the existing tests keep passing.
        val info = BusyInfo("s4", "task", null, null)
        val reasonDefault = composeBusyDeclineReason(info)
        val expectedSeconds = AgentController.ACCEPT_WINDOW_MS / 1000
        assertTrue(
            reasonDefault.contains("${expectedSeconds}s"),
            "default window in reason must match ACCEPT_WINDOW_MS (${expectedSeconds}s): $reasonDefault"
        )
    }
}

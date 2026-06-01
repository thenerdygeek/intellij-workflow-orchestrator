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
        assertTrue(reason.contains("did not accept the takeover"), reason)
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
        assertTrue(reason.contains("did not accept the takeover"))
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
}

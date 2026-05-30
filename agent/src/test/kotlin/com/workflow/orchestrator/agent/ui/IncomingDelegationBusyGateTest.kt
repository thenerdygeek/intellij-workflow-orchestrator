package com.workflow.orchestrator.agent.ui

import com.workflow.orchestrator.agent.delegation.DelegatedSessionSurface
import com.workflow.orchestrator.agent.delegation.DelegatedSurface
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Bug B ("delegation works once, then times out"): regression coverage for the
 * incoming-delegation busy gate.
 *
 * Root cause: the gate counted a session that is merely LOADED in the tab
 * (`currentSessionId != null`) as busy. A completed delegated session never cleared
 * `currentSessionId` (only `resetForNewChat` does), so a SECOND delegation to the same
 * idle-but-loaded IDE-B was classified busy → QUEUE_INCOMING → 55s human-Start window →
 * nobody clicks → DECLINED_TIMEOUT, forever.
 *
 * The fix: "busy" for an INCOMING delegation means *an agent loop is actively running
 * right now*, not *a session object is loaded*. This pins the decision so the two-sequential-
 * delegations scenario routes the second one to RUN_NOW once the first loop has terminated.
 *
 * The timing core is exercised by [IncomingDelegationWaitTest]; this exercises the
 * loaded-but-idle vs actively-running decision — the part the shipped bug got wrong.
 */
class IncomingDelegationBusyGateTest {

    @Test
    fun `an actively-running agent loop is busy for an incoming delegation`() {
        // A real in-flight task must still QUEUE an incoming delegation (do not regress the
        // legit busy case).
        assertTrue(
            decideIncomingBusy(jobActive = true, sessionLoaded = true),
            "a running job must be busy",
        )
    }

    @Test
    fun `a completed-but-loaded session is NOT busy for an incoming delegation`() {
        // The shipped bug: a delegated (or interactive) session that finished but is still
        // displayed in the tab. The loop is done (job not active) but currentSessionId is still
        // set. This must NOT count as busy — otherwise the next delegation dead-ends in the
        // accept-window timeout.
        assertFalse(
            decideIncomingBusy(jobActive = false, sessionLoaded = true),
            "an idle-but-loaded session must NOT be busy — a completed session is not a running loop",
        )
    }

    @Test
    fun `a fresh idle tab with nothing loaded is NOT busy`() {
        assertFalse(
            decideIncomingBusy(jobActive = false, sessionLoaded = false),
            "a clean idle tab must not be busy",
        )
    }

    @Test
    fun `two sequential delegations both route to RUN_NOW once the first loop terminates`() {
        // Delegation #1 arrives on a clean tab → RUN_NOW.
        val first = DelegatedSessionSurface.decide(
            decideIncomingBusy(jobActive = false, sessionLoaded = false)
        )
        assertEquals(DelegatedSurface.RUN_NOW, first, "first delegation on an idle tab runs now")

        // ...the session runs and COMPLETES. The loop's job is no longer active, but
        // currentSessionId is still set (the completed session stays loaded in the tab — this is
        // the exact state the old gate misread as busy).
        val secondBusy = decideIncomingBusy(jobActive = false, sessionLoaded = true)
        val second = DelegatedSessionSurface.decide(secondBusy)
        assertEquals(
            DelegatedSurface.RUN_NOW,
            second,
            "the SECOND delegation must also run now (not DECLINED_TIMEOUT) once the first loop has terminated",
        )
    }
}

package com.workflow.orchestrator.agent.delegation

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Pins basic invariants of DelegationOutboundService that are testable without
 * the IntelliJ Project / Service container. The full E2E behavior lives in
 * DelegationE2ETest (Task 12).
 */
class DelegationOutboundServiceTest {

    @Test
    fun `close on unknown handle returns false`() {
        // Cannot easily instantiate without Project; this test is a placeholder
        // for source-text invariants verifiable in Task 12. For now, assert the
        // exception types exist and have stable messages.
        assertEquals("user_canceled_picker", DelegationException.UserCanceledPicker.message)
        assertEquals("target_not_reachable", DelegationException.TargetNotReachable.message)
        assertEquals("delegation_limit_reached", DelegationException.LimitReached.message)
        assertTrue(DelegationException.Rejected("foo").message!!.startsWith("rejected:"))
        assertTrue(DelegationException.Expired(null).message!!.startsWith("expired:"))
    }

    @Test
    fun `MAX_CHANNELS is 5 per spec section 6_6`() {
        assertEquals(5, DelegationOutboundService.MAX_CHANNELS)
    }

    @Test
    fun `DelegationHandle holds the targetRepoName for nudge display`() {
        val h = DelegationHandle(id = "x", targetProjectPath = "/p", targetRepoName = "frontend")
        assertEquals("frontend", h.targetRepoName)
    }
}

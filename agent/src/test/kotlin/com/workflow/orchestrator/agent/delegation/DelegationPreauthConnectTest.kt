package com.workflow.orchestrator.agent.delegation

import com.intellij.openapi.project.Project
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Pins the single-use preauth-nonce registry on [DelegationInboundService] (Plan 6 Task 4).
 *
 * The registry is a security gate: a consented nonce must skip the Accept dialog
 * for exactly ONE Connect. A leaked multi-use nonce would let a delegator bypass
 * consent on every subsequent connection, so [DelegationInboundService.consumePreauth]
 * MUST return true at most once per recorded nonce.
 *
 * The registry methods touch no IDE services, so relaxed mocks for Project / scope suffice.
 */
class DelegationPreauthConnectTest {
    private fun newService() =
        DelegationInboundService(mockk<Project>(relaxed = true), CoroutineScope(Job()))

    @Test
    fun `consumePreauth matches a recorded nonce exactly once`() {
        val svc = newService()
        svc.recordPreauth("n1")
        assertTrue(svc.consumePreauth("n1"), "first match should succeed")
        assertFalse(svc.consumePreauth("n1"), "single-use: second match must fail")
    }

    @Test
    fun `consumePreauth returns false for unknown or null nonce`() {
        val svc = newService()
        assertFalse(svc.consumePreauth("never-recorded"))
        assertFalse(svc.consumePreauth(null))
    }
}

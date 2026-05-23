package com.workflow.orchestrator.agent.delegation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DelegationExceptionTest {
    @Test
    fun `IdleTimedOut carries handle and lastSeenAt`() {
        val handle = DelegationHandle(
            id = "h-1",
            targetProjectPath = "/repo/b",
            targetRepoName = "frontend-app",
        )
        val ex = DelegationException.IdleTimedOut(handle = handle, lastSeenAt = 1_700_000_000_000L)
        assertEquals("h-1", ex.handle.id)
        assertEquals(1_700_000_000_000L, ex.lastSeenAt)
        assertTrue(ex.message!!.contains("idle_timeout"))
    }
}

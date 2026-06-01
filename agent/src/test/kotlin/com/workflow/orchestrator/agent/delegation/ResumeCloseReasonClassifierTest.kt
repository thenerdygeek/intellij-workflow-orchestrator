package com.workflow.orchestrator.agent.delegation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * TDD tests for [classifyResumeCloseReason].
 *
 * Contract:
 * - Known taxonomy tokens (ide_b_busy, resume_failed, ide_b_not_running,
 *   ide_b_agent_unavailable, resume_protocol_error) are returned VERBATIM — no
 *   "session_closed:" prefix.
 * - Genuine terminal closes (completed/canceled/failed, bare/empty/null) are
 *   wrapped as "session_closed: <reason>".
 *
 * Spec: cross-ide-delegation/SKILL.md §53-57 + tool-docs/delegation.md §308.
 */
class ResumeCloseReasonClassifierTest {

    // ───── Known taxonomy tokens → verbatim ─────────────────────────────────

    @Test
    fun `ide_b_busy reason is returned verbatim without session_closed prefix`() {
        val reason = "ide_b_busy: agent tab is running another task; could not resume"
        val result = classifyResumeCloseReason(reason)
        assertEquals(reason, result)
        assertFalse(result.startsWith("session_closed:"), "must NOT start with session_closed:")
    }

    @Test
    fun `resume_failed reason is returned verbatim without session_closed prefix`() {
        val reason = "resume_failed: session id not delivered in time"
        val result = classifyResumeCloseReason(reason)
        assertEquals(reason, result)
        assertFalse(result.startsWith("session_closed:"), "must NOT start with session_closed:")
    }

    @Test
    fun `ide_b_not_running reason is returned verbatim without session_closed prefix`() {
        val reason = "ide_b_not_running"
        val result = classifyResumeCloseReason(reason)
        assertEquals(reason, result)
        assertFalse(result.startsWith("session_closed:"), "must NOT start with session_closed:")
    }

    @Test
    fun `ide_b_agent_unavailable reason is returned verbatim without session_closed prefix`() {
        val reason = "ide_b_agent_unavailable"
        val result = classifyResumeCloseReason(reason)
        assertEquals(reason, result)
        assertFalse(result.startsWith("session_closed:"), "must NOT start with session_closed:")
    }

    @Test
    fun `resume_protocol_error reason is returned verbatim without session_closed prefix`() {
        val reason = "resume_protocol_error: follow-up turn not received"
        val result = classifyResumeCloseReason(reason)
        assertEquals(reason, result)
        assertFalse(result.startsWith("session_closed:"), "must NOT start with session_closed:")
    }

    @Test
    fun `ide_b_busy with trailing context suffix is returned verbatim`() {
        // The summary appendage " — <summary text>" must be preserved when the caller
        // adds it AFTER classifyResumeCloseReason(), not stripped or double-wrapped.
        val baseReason = "ide_b_busy: agent tab is running another task; could not resume"
        val withSuffix = "$baseReason — summary text here"
        val result = classifyResumeCloseReason(withSuffix)
        assertEquals(withSuffix, result)
        assertFalse(result.startsWith("session_closed:"))
    }

    // ───── Genuine terminal / bare / unknown reasons → wrapped ───────────────

    @Test
    fun `completed reason is wrapped with session_closed prefix`() {
        val result = classifyResumeCloseReason("completed")
        assertEquals("session_closed: completed", result)
    }

    @Test
    fun `canceled reason is wrapped with session_closed prefix`() {
        val result = classifyResumeCloseReason("canceled")
        assertEquals("session_closed: canceled", result)
    }

    @Test
    fun `failed reason is wrapped with session_closed prefix`() {
        val result = classifyResumeCloseReason("failed")
        assertEquals("session_closed: failed", result)
    }

    @Test
    fun `bare unknown reason is wrapped with session_closed prefix`() {
        val result = classifyResumeCloseReason("some_unknown_close_reason")
        assertEquals("session_closed: some_unknown_close_reason", result)
    }

    @Test
    fun `empty string reason is wrapped with session_closed prefix`() {
        val result = classifyResumeCloseReason("")
        assertEquals("session_closed: ", result)
    }

    @Test
    fun `null reason is wrapped as session_closed with empty tail`() {
        val result = classifyResumeCloseReason(null)
        assertEquals("session_closed: ", result)
    }

    // ───── DelegationException.Expired shape after classifier ────────────────

    @Test
    fun `Expired wrapping verbatim reason does not double-prefix`() {
        val reason = "ide_b_busy: agent tab is running another task; could not resume"
        val ex = DelegationException.Expired(classifyResumeCloseReason(reason))
        // The exception's expireReason is the classified string, without session_closed prefix.
        assertEquals(reason, ex.expireReason)
        assertTrue(ex.message!!.startsWith("expired:"))
        assertFalse(ex.message!!.contains("session_closed:"))
    }

    @Test
    fun `Expired wrapping completed reason contains session_closed prefix`() {
        val ex = DelegationException.Expired(classifyResumeCloseReason("completed"))
        assertEquals("session_closed: completed", ex.expireReason)
        assertTrue(ex.message!!.contains("session_closed: completed"))
    }
}

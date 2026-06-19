package com.workflow.orchestrator.agent.tools.cancel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.coroutines.cancellation.CancellationException

class ToolStopSupportTest {

    @Test
    fun `isUserStop is true for the sentinel`() {
        assertTrue(isUserStop(UserStopCancellationException("t1")))
    }

    @Test
    fun `isUserStop is true when the sentinel is nested as a cause`() {
        val wrapped = CancellationException("outer")
        wrapped.initCause(UserStopCancellationException("t1"))
        assertTrue(isUserStop(wrapped))
    }

    @Test
    fun `isUserStop is false for a plain cancellation`() {
        assertFalse(isUserStop(CancellationException("just a timeout or loop cancel")))
    }

    @Test
    fun `stoppedByUserResult is non-error, summarized, and names the tool`() {
        val r = stoppedByUserResult("web_fetch")
        assertFalse(r.isError)
        assertEquals("Stopped by user", r.summary)
        assertTrue(r.content.contains("web_fetch"))
        assertTrue(r.content.lowercase().contains("stopped by the user"))
    }
}

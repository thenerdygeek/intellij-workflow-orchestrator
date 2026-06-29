package com.workflow.orchestrator.agent.tools.cancel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ToolStopCoordinatorTest {

    @Test
    fun `when a process is killed, the coroutine job is ALSO cancelled (BUG-STOP-1 B1)`() {
        // BUG-STOP-1 B1: Stop must kill the OS process AND cancel the tool coroutine.
        // The old short-circuit (`if (killProcess) return true`) left run_command's
        // monitor loop spinning for the command's natural runtime.
        var cancelledId: String? = null
        val result = ToolStopCoordinator.requestStop(
            "t1",
            killProcess = { true },
            cancelCoroutine = {
                cancelledId = it
                true
            },
        )
        assertTrue(result)
        assertEquals("t1", cancelledId, "coroutine cancel MUST run even when a process was killed")
    }

    @Test
    fun `returns true when only the process was registered (coroutine already gone)`() {
        val result = ToolStopCoordinator.requestStop(
            "t1b",
            killProcess = { true },
            cancelCoroutine = { false },
        )
        assertTrue(result, "killing the process alone is still a successful stop")
    }

    @Test
    fun `falls back to coroutine cancel when no process is registered`() {
        var cancelledId: String? = null
        val result = ToolStopCoordinator.requestStop(
            "t2",
            killProcess = { false },
            cancelCoroutine = {
                cancelledId = it
                true
            },
        )
        assertTrue(result)
        assertEquals("t2", cancelledId)
    }

    @Test
    fun `returns false when neither a process nor a tool-call job matches`() {
        val result = ToolStopCoordinator.requestStop(
            "t3",
            killProcess = { false },
            cancelCoroutine = { false },
        )
        assertFalse(result)
    }
}

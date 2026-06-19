package com.workflow.orchestrator.agent.tools.cancel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ToolStopCoordinatorTest {

    @Test
    fun `process precedence - when a process is killed, coroutine cancel is NOT attempted`() {
        var coroutineAttempted = false
        val result = ToolStopCoordinator.requestStop(
            "t1",
            killProcess = { true },
            cancelCoroutine = {
                coroutineAttempted = true
                true
            },
        )
        assertTrue(result)
        assertFalse(coroutineAttempted, "coroutine cancel must not run when a process was killed")
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

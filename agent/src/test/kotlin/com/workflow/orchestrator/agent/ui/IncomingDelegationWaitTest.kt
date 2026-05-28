package com.workflow.orchestrator.agent.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Behavioral coverage for the busy-path accept-window wait (review finding I4).
 *
 * The timing core of `AgentController.startDelegatedSession`'s QUEUE_INCOMING branch is factored
 * into the pure suspend helper [awaitIncomingStart] so it can be exercised headless with
 * kotlinx-coroutines-test virtual time — no Project/Application/EDT required.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IncomingDelegationWaitTest {

    @Test
    fun `returns true when the gate completes within the window`() = runTest {
        val gate = CompletableDeferred<Boolean>()
        val result = async { awaitIncomingStart(gate, windowMs = 55_000L) }
        // Human clicks Start partway through the window.
        advanceTimeBy(10_000L)
        runCurrent()
        gate.complete(true)
        assertTrue(result.await(), "a Start click within the window must resolve to true (STARTED)")
    }

    @Test
    fun `returns false when the window elapses with no Start`() = runTest {
        val gate = CompletableDeferred<Boolean>()
        val result = async { awaitIncomingStart(gate, windowMs = 55_000L) }
        // No Start; let the whole window (plus a tick) elapse on virtual time.
        advanceTimeBy(55_001L)
        runCurrent()
        assertFalse(result.await(), "an elapsed window with no Start must resolve to false (DECLINED_TIMEOUT)")
    }

    @Test
    fun `does not return early before the window when the gate is never completed`() = runTest {
        val gate = CompletableDeferred<Boolean>()
        val result = async { awaitIncomingStart(gate, windowMs = 55_000L) }
        advanceTimeBy(54_000L) // still inside the window
        runCurrent()
        assertFalse(result.isCompleted, "must keep waiting until the window actually elapses")
        advanceTimeBy(2_000L) // cross the deadline
        runCurrent()
        assertEquals(false, result.await())
    }

    @Test
    fun `ACCEPT_WINDOW_MS is strictly less than IDE-A's 60s accept timeout`() {
        assertTrue(
            AgentController.ACCEPT_WINDOW_MS < 60_000L,
            "ACCEPT_WINDOW_MS must stay strictly below IDE-A's connectAndAwaitAccept timeout (60_000L)",
        )
    }
}

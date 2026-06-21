package com.workflow.orchestrator.agent.tools.background

import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BackgroundToolExecutorTest {
    private fun handle(id: String, session: String = "S1") =
        BackgroundToolHandle(id, session, "run_command", 0L)

    @Test
    fun `agent-initiated background delivers result on completion`() = runTest {
        val delivered = ArrayList<Pair<String, ToolResult>>()
        val reg = BackgroundToolRegistry()
        val exec = BackgroundToolExecutor(CoroutineScope(SupervisorJob()), reg) { h, r -> delivered += h.toolCallId to r }
        val h = handle("t1").also { it.backgrounded = true }   // launched backgrounded up-front
        val gate = CompletableDeferred<Unit>()
        exec.start(h) { gate.await(); ToolResult("done", "ok", 3) }
        gate.complete(Unit)
        h.job.join()
        assertEquals(1, delivered.size)
        assertEquals("t1", delivered[0].first)
        assertEquals("done", delivered[0].second.content)
        assertEquals(0, reg.countForSession("S1"))   // unregistered on completion
    }

    @Test
    fun `inline completion does NOT deliver to queue`() = runTest {
        val delivered = ArrayList<String>()
        val reg = BackgroundToolRegistry()
        val exec = BackgroundToolExecutor(CoroutineScope(SupervisorJob()), reg) { h, _ -> delivered += h.toolCallId }
        val h = handle("t2")  // backgrounded stays false (inline)
        exec.start(h) { ToolResult("x", "x", 1) }
        val result = h.deferred.await()     // loop awaits inline
        h.job.join()
        assertEquals("x", result.content)
        assertTrue(delivered.isEmpty())     // inline path delivers nothing via queue
    }

    @Test
    fun `detach lets the job run on and then delivers`() = runTest {
        val delivered = ArrayList<String>()
        val reg = BackgroundToolRegistry()
        val exec = BackgroundToolExecutor(CoroutineScope(SupervisorJob()), reg) { h, _ -> delivered += h.toolCallId }
        val h = handle("t3")
        val gate = CompletableDeferred<Unit>()
        exec.start(h) { gate.await(); ToolResult("late", "late", 2) }
        // user clicks "Move to background" before the tool finishes:
        assertTrue(exec.detach("t3"))
        assertTrue(h.backgrounded)
        gate.complete(Unit)
        h.job.join()
        assertEquals(listOf("t3"), delivered)   // delivered via queue after detach
    }

    @Test
    fun `cancelAllForSession cancels in-flight jobs`() = runTest {
        val reg = BackgroundToolRegistry()
        val exec = BackgroundToolExecutor(CoroutineScope(SupervisorJob()), reg) { _, _ -> }
        val h = handle("t4")
        val never = CompletableDeferred<Unit>()
        exec.start(h) { never.await(); ToolResult("never", "never", 1) }
        exec.cancelAllForSession("S1")
        h.job.join()
        assertTrue(h.job.isCancelled)
        assertEquals(0, reg.countForSession("S1"))
    }

    @Test
    fun `tool exception becomes an error ToolResult, not a thrown exception`() = runTest {
        val reg = BackgroundToolRegistry()
        val exec = BackgroundToolExecutor(CoroutineScope(SupervisorJob()), reg) { _, _ -> }
        val h = handle("t5")
        exec.start(h) { throw IllegalStateException("boom") }
        val r = h.deferred.await()   // onAwait must NOT rethrow
        assertTrue(r.isError)
        assertTrue(r.content.contains("boom"))
    }

    @Test
    fun `claimDelivery succeeds exactly once`() {
        val h = handle("c1")
        assertTrue(h.claimDelivery())
        assertFalse(h.claimDelivery())
    }

    @Test
    fun `does not double-deliver when the loop already claimed the result inline`() = runTest {
        val delivered = ArrayList<String>()
        val reg = BackgroundToolRegistry()
        val exec = BackgroundToolExecutor(CoroutineScope(SupervisorJob()), reg) { h, _ -> delivered += h.toolCallId }
        val h = handle("c2").also { it.backgrounded = true }
        // Simulate the loop's inline select winning the single delivery claim first (detach-vs-completion race):
        assertTrue(h.claimDelivery())
        exec.start(h) { ToolResult("x", "x", 1) }
        h.job.join()
        assertTrue(delivered.isEmpty())  // executor's invokeOnCompletion saw the claim taken → no queue double-delivery
    }
}

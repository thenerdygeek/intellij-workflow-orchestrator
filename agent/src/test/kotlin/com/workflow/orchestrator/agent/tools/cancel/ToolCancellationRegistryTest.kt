package com.workflow.orchestrator.agent.tools.cancel

import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ToolCancellationRegistryTest {

    @AfterEach
    fun cleanup() {
        // registry is a global object; defensively clear any leftover ids used below
        listOf("t1", "t2").forEach { ToolCancellationRegistry.unregister(it) }
    }

    @Test
    fun `cancel returns false when no job is registered`() {
        assertFalse(ToolCancellationRegistry.cancel("missing"))
    }

    @Test
    fun `register then isActive is true, unregister clears it`() {
        val job = Job()
        ToolCancellationRegistry.register("t1", job)
        assertTrue(ToolCancellationRegistry.isActive("t1"))
        ToolCancellationRegistry.unregister("t1")
        assertFalse(ToolCancellationRegistry.isActive("t1"))
    }

    @Test
    @OptIn(InternalCoroutinesApi::class)
    fun `cancel cancels the job with a UserStop cause, removes it, and returns true`() = runTest {
        val job = Job()
        ToolCancellationRegistry.register("t2", job)

        assertTrue(ToolCancellationRegistry.cancel("t2"))
        assertTrue(job.isCancelled)

        val ce = job.getCancellationException()
        assertTrue(
            ce is UserStopCancellationException || ce.cause is UserStopCancellationException,
            "cancellation cause must be (or wrap) UserStopCancellationException, was $ce",
        )
        // second cancel finds nothing
        assertFalse(ToolCancellationRegistry.cancel("t2"))
    }
}

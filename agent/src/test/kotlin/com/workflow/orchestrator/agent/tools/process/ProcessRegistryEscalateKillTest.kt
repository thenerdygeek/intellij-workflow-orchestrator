package com.workflow.orchestrator.agent.tools.process

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

/**
 * BUG-STOP-1 B2 — the kill must force-kill the FULL descendant tree, not just the
 * parent shell. A reparented child (e.g. `grep` forked by `bash -l -c`) that survives
 * the parent's death must still receive SIGKILL.
 *
 * [ProcessRegistry.escalateKill] is the extracted, synchronously-testable escalation
 * phase (the real call offloads it to a daemon executor). These tests mock the Process
 * + descendant ProcessHandles so no real OS process is spawned.
 */
class ProcessRegistryEscalateKillTest {

    @Test
    fun `surviving descendant is destroyForcibly even after the parent exits gracefully`() {
        // Parent exits within the graceful window — but a child outlives it.
        val parent = mockk<Process>(relaxed = true)
        every { parent.waitFor(any(), any()) } returns true

        val survivingChild = mockk<ProcessHandle>(relaxed = true)
        every { survivingChild.isAlive } returns true
        every { survivingChild.destroyForcibly() } returns true

        ProcessRegistry.escalateKill(parent, listOf(survivingChild), gracefulWaitMs = 10)

        // Parent exited gracefully → no SIGKILL on the parent…
        verify(exactly = 0) { parent.destroyForcibly() }
        // …but the surviving child MUST be force-killed (the BUG-STOP-1 leak).
        verify(exactly = 1) { survivingChild.destroyForcibly() }
    }

    @Test
    fun `parent that survives SIGTERM is force-killed, and so are live descendants`() {
        val parent = mockk<Process>(relaxed = true)
        every { parent.waitFor(any(), any()) } returns false // never exits on SIGTERM
        every { parent.destroyForcibly() } returns parent

        val liveChild = mockk<ProcessHandle>(relaxed = true)
        every { liveChild.isAlive } returns true
        every { liveChild.destroyForcibly() } returns true

        val deadChild = mockk<ProcessHandle>(relaxed = true)
        every { deadChild.isAlive } returns false

        ProcessRegistry.escalateKill(parent, listOf(liveChild, deadChild), gracefulWaitMs = 10)

        verify(atLeast = 1) { parent.destroyForcibly() }
        verify(exactly = 1) { liveChild.destroyForcibly() }
        // Already-dead descendant is not force-killed again.
        verify(exactly = 0) { deadChild.destroyForcibly() }
    }

    @Test
    fun `empty descendant list is handled (parent-only process)`() {
        val parent = mockk<Process>(relaxed = true)
        every { parent.waitFor(any(), any()) } returns true

        // Must not throw.
        ProcessRegistry.escalateKill(parent, emptyList(), gracefulWaitMs = 10)

        verify(exactly = 0) { parent.destroyForcibly() }
        verify { parent.waitFor(10L, TimeUnit.MILLISECONDS) }
    }
}

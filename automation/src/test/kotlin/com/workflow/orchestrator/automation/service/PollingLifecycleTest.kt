package com.workflow.orchestrator.automation.service

import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.Job
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for [PollingLifecycle] — the small abstraction that owns
 * `QueueService.pollingJob` and guarantees the check-and-clear /
 * check-and-launch operations are atomic against each other.
 *
 * Pre-refactor, `QueueService.startPollingIfNeeded` and the in-loop
 * exit decision were two unsynchronized reads/writes of the same field.
 * That left a microsecond window where the poller had decided to exit
 * but its Job was still `isActive == true`, so an `enqueue` racing
 * with the exit would skip launching a fresh poller and silently
 * orphan the new entry in `WAITING_LOCAL` forever.
 *
 * This file pins the atomicity contract.
 */
class PollingLifecycleTest {

    private fun activeJob(): CompletableJob = Job()

    @Test
    fun `startIfNeeded launches when no current job`() {
        val lifecycle = PollingLifecycle()
        val newJob = activeJob()

        val launched = lifecycle.startIfNeeded { newJob }

        assertTrue(launched)
        assertSame(newJob, lifecycle.currentJobForTest)
    }

    @Test
    fun `startIfNeeded does not launch when current job is active`() {
        val lifecycle = PollingLifecycle()
        val firstJob = activeJob()
        lifecycle.startIfNeeded { firstJob }

        var launchInvoked = false
        val launched = lifecycle.startIfNeeded {
            launchInvoked = true
            activeJob()
        }

        assertFalse(launched)
        assertFalse(launchInvoked, "launch block must not run when a poller is already active")
        assertSame(firstJob, lifecycle.currentJobForTest)
    }

    @Test
    fun `startIfNeeded launches again after the previous job completes`() {
        val lifecycle = PollingLifecycle()
        val firstJob = activeJob()
        lifecycle.startIfNeeded { firstJob }
        firstJob.complete()

        val secondJob = activeJob()
        val launched = lifecycle.startIfNeeded { secondJob }

        assertTrue(launched)
        assertSame(secondJob, lifecycle.currentJobForTest)
    }

    @Test
    fun `tryExit returns false and keeps job when work remains`() {
        val lifecycle = PollingLifecycle()
        val job = activeJob()
        lifecycle.startIfNeeded { job }

        val shouldExit = lifecycle.tryExit(self = job, hasLiveWork = { true })

        assertFalse(shouldExit)
        assertSame(job, lifecycle.currentJobForTest,
            "tryExit must NOT clear state while work remains")
    }

    @Test
    fun `tryExit returns true and clears job when no work remains`() {
        val lifecycle = PollingLifecycle()
        val job = activeJob()
        lifecycle.startIfNeeded { job }

        val shouldExit = lifecycle.tryExit(self = job, hasLiveWork = { false })

        assertTrue(shouldExit)
        assertEquals(null, lifecycle.currentJobForTest,
            "tryExit must atomically clear state so the next startIfNeeded launches")
    }

    @Test
    fun `tryExit followed by startIfNeeded launches a new poller (the race fix)`() {
        // This is the core invariant flaw #4 violated: after the old poller decides
        // to exit, a subsequent startIfNeeded must launch a new one — even if the
        // old job's Kotlin `Job` instance is still technically `isActive` while its
        // coroutine body is in the middle of returning. PollingLifecycle ensures
        // this by clearing currentJob inside the same synchronized block as the
        // exit decision, so startIfNeeded reads `null` and proceeds.
        val lifecycle = PollingLifecycle()
        val oldJob = activeJob()  // intentionally NOT completed — simulates the race window
        lifecycle.startIfNeeded { oldJob }

        // Old poller's exit decision: lock acquired, decides to break, clears state.
        assertTrue(lifecycle.tryExit(self = oldJob, hasLiveWork = { false }))

        // Enqueue racing with the exit: startIfNeeded must see the cleared slot and launch.
        val newJob = activeJob()
        var launchInvoked = false
        val launched = lifecycle.startIfNeeded {
            launchInvoked = true
            newJob
        }

        assertTrue(launched, "race fix: a new poller must launch after tryExit clears the slot")
        assertTrue(launchInvoked)
        assertSame(newJob, lifecycle.currentJobForTest)
    }

    @Test
    fun `tryExit returns true silently when self is no longer the active job`() {
        // Defensive case: a poller's body wakes after delay, but in the meantime
        // its job was cancelled and replaced.  tryExit should report "exit" without
        // touching the (possibly newer) currentJob slot.
        val lifecycle = PollingLifecycle()
        val firstJob = activeJob()
        lifecycle.startIfNeeded { firstJob }
        firstJob.complete()

        val secondJob = activeJob()
        lifecycle.startIfNeeded { secondJob }

        val shouldExit = lifecycle.tryExit(self = firstJob, hasLiveWork = { true })

        assertTrue(shouldExit, "a poller whose job is no longer current must exit silently")
        assertSame(secondJob, lifecycle.currentJobForTest,
            "tryExit must not touch the slot when self does not match")
    }
}

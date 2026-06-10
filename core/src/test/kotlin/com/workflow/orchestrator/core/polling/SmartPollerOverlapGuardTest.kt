package com.workflow.orchestrator.core.polling

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * B13 (2026-06-10 perf audit): `setVisible(true)` used to launch `action()` in a fresh
 * coroutine CONCURRENTLY with the poll loop — two overlapping HTTP refreshes of the same
 * resource. These tests pin the serialization contract: the action never runs concurrently
 * with itself, while the visibility-resume poll still executes (after the in-flight poll).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SmartPollerOverlapGuardTest {

    @Test
    fun `visibility-resume poll never overlaps an in-flight loop poll`() = runTest {
        val active = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)
        val polls = AtomicInteger(0)

        val poller = SmartPoller(
            name = "overlap",
            baseIntervalMs = 30_000,
            maxIntervalMs = 300_000,
            scope = this,
            networkProbe = null,
            ideFocused = { true },
            action = {
                val now = active.incrementAndGet()
                maxConcurrent.updateAndGet { maxOf(it, now) }
                polls.incrementAndGet()
                delay(5_000) // slow poll — keeps the action in flight in virtual time
                active.decrementAndGet()
                false
            },
        )

        poller.start()
        runCurrent() // loop's first poll enters the action, suspended in delay(5_000)

        // The debounce window is measured on the REAL clock (System.currentTimeMillis),
        // so sleep past it for real before flipping visibility back on.
        poller.setVisible(false)
        Thread.sleep(SmartPoller.VISIBILITY_DEBOUNCE_MS + 200)
        poller.setVisible(true) // fires the resume poll while the loop poll is still in flight
        runCurrent()

        advanceTimeBy(20_000)
        runCurrent()
        poller.stop()

        assertTrue(polls.get() >= 2, "resume poll must still run (serialized after the loop poll), got ${polls.get()}")
        assertEquals(1, maxConcurrent.get(), "action must never run concurrently with itself")
    }

    @Test
    fun `serialized resume poll does not change loop delay math`() = runTest {
        // Regression guard for the Wave-1 focus-gate behavior: adding serialization must not
        // alter the loop's interval computation. Mirrors SmartPollerFocusGateTest's timing.
        val polls = AtomicInteger(0)
        val poller = SmartPoller(
            name = "timing",
            baseIntervalMs = 30_000,
            maxIntervalMs = 300_000,
            scope = this,
            networkProbe = null,
            ideFocused = { true },
            action = {
                polls.incrementAndGet()
                false
            },
        )
        poller.start()
        // Poll at t=0; next delay in [1.35, 1.65]·base (backoff 1.5, ±10% jitter).
        advanceTimeBy(75_000)
        poller.stop()
        assertTrue(polls.get() >= 2, "poller should have re-polled within 75s (got ${polls.get()})")
    }
}

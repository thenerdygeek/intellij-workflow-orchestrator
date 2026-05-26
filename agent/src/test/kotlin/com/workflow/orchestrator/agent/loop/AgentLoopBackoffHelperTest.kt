package com.workflow.orchestrator.agent.loop

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * computeBackoffMs uses EQUAL jitter: result in [computed/2, computed]. The lower half is a
 * fixed floor so no retry is ever near-instant; the upper half is randomized to spread load.
 * These tests pin both the floor and the ceiling at each attempt.
 */
class AgentLoopBackoffHelperTest {

    @Test
    fun `attempt 1 base 1000 returns in 500 to 1000 inclusive`() {
        repeat(200) {
            val d = AgentLoop.computeBackoffMs(attempt = 1, baseMs = 1000L)
            assertTrue(d in 500L..1000L) { "attempt=1 base=1000 produced $d, expected [500, 1000]" }
        }
    }

    @Test
    fun `attempt 2 base 1000 returns in 1000 to 2000 inclusive`() {
        repeat(200) {
            val d = AgentLoop.computeBackoffMs(attempt = 2, baseMs = 1000L)
            assertTrue(d in 1000L..2000L) { "attempt=2 produced $d, expected [1000, 2000]" }
        }
    }

    @Test
    fun `attempt 5 base 1000 cap 30000 returns in 8000 to 16000 inclusive`() {
        repeat(200) {
            val d = AgentLoop.computeBackoffMs(attempt = 5, baseMs = 1000L, capMs = 30_000L)
            assertTrue(d in 8_000L..16_000L) { "attempt=5 produced $d, expected [8000, 16000]" }
        }
    }

    @Test
    fun `attempt 10 saturates at cap with floor`() {
        repeat(200) {
            val d = AgentLoop.computeBackoffMs(attempt = 10, baseMs = 1000L, capMs = 30_000L)
            assertTrue(d in 15_000L..30_000L) { "attempt=10 produced $d, expected [15000, 30000]" }
        }
    }

    @Test
    fun `attempt 100 does not overflow and still respects cap with floor`() {
        repeat(200) {
            val d = AgentLoop.computeBackoffMs(attempt = 100, baseMs = 1000L, capMs = 30_000L)
            assertTrue(d in 15_000L..30_000L) { "attempt=100 produced $d, expected [15000, 30000]" }
        }
    }

    @Test
    fun `retryAfter overrides exponential and is jittered with floor`() {
        repeat(200) {
            val d = AgentLoop.computeBackoffMs(attempt = 5, baseMs = 1000L, retryAfterMs = 5_000L)
            assertTrue(d in 2_500L..5_000L) { "retryAfter=5000 produced $d, expected [2500, 5000]" }
        }
    }

    @Test
    fun `retryAfter is capped to capMs with floor`() {
        repeat(200) {
            val d = AgentLoop.computeBackoffMs(attempt = 1, capMs = 30_000L, retryAfterMs = 999_999_999L)
            assertTrue(d in 15_000L..30_000L) { "retryAfter=999M capped should land in [15000, 30000], got $d" }
        }
    }

    @Test
    fun `statistical mean is roughly three quarters of computed upper bound`() {
        // attempt=3, base=1000 → computed=4000. Equal jitter → Uniform[2000, 4000], mean ≈ 3000. Allow ±10%.
        val samples = (1..2000).map { AgentLoop.computeBackoffMs(attempt = 3, baseMs = 1000L) }
        val mean = samples.average()
        assertTrue(mean in 2700.0..3300.0) {
            "Expected mean ~3000 (±10%) from Uniform[2000,4000], got $mean across ${samples.size} samples"
        }
    }

    @Test
    fun `base 200 attempt 1 stays in 100 to 200`() {
        repeat(200) {
            val d = AgentLoop.computeBackoffMs(attempt = 1, baseMs = 200L)
            assertTrue(d in 100L..200L) { "compaction base=200 attempt=1 produced $d, expected [100, 200]" }
        }
    }
}

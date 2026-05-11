package com.workflow.orchestrator.agent.loop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AgentLoopBackoffHelperTest {

    @Test
    fun `attempt 1 base 1000 returns in 0 to 1000 inclusive`() {
        repeat(200) {
            val d = AgentLoop.computeBackoffMs(attempt = 1, baseMs = 1000L)
            assertTrue(d in 0L..1000L) { "attempt=1 base=1000 produced $d, expected [0, 1000]" }
        }
    }

    @Test
    fun `attempt 2 base 1000 returns in 0 to 2000 inclusive`() {
        repeat(200) {
            val d = AgentLoop.computeBackoffMs(attempt = 2, baseMs = 1000L)
            assertTrue(d in 0L..2000L) { "attempt=2 produced $d, expected [0, 2000]" }
        }
    }

    @Test
    fun `attempt 5 base 1000 cap 30000 returns in 0 to 16000 inclusive`() {
        repeat(200) {
            val d = AgentLoop.computeBackoffMs(attempt = 5, baseMs = 1000L, capMs = 30_000L)
            assertTrue(d in 0L..16_000L) { "attempt=5 produced $d, expected [0, 16000]" }
        }
    }

    @Test
    fun `attempt 10 saturates at cap`() {
        repeat(200) {
            val d = AgentLoop.computeBackoffMs(attempt = 10, baseMs = 1000L, capMs = 30_000L)
            assertTrue(d in 0L..30_000L) { "attempt=10 produced $d, expected [0, 30000]" }
        }
    }

    @Test
    fun `attempt 100 does not overflow and still respects cap`() {
        repeat(200) {
            val d = AgentLoop.computeBackoffMs(attempt = 100, baseMs = 1000L, capMs = 30_000L)
            assertTrue(d in 0L..30_000L) { "attempt=100 produced $d, expected [0, 30000]" }
        }
    }

    @Test
    fun `retryAfter overrides exponential and is jittered`() {
        repeat(200) {
            val d = AgentLoop.computeBackoffMs(attempt = 5, baseMs = 1000L, retryAfterMs = 5_000L)
            assertTrue(d in 0L..5_000L) { "retryAfter=5000 produced $d, expected [0, 5000]" }
        }
    }

    @Test
    fun `retryAfter is capped to capMs`() {
        repeat(200) {
            val d = AgentLoop.computeBackoffMs(attempt = 1, capMs = 30_000L, retryAfterMs = 999_999_999L)
            assertTrue(d in 0L..30_000L) { "retryAfter=999M capped should land in [0, 30000], got $d" }
        }
    }

    @Test
    fun `statistical mean is roughly half of computed upper bound`() {
        // attempt=3, base=1000 → computed=4000. Mean of Uniform[0, 4000] ≈ 2000. Allow ±15%.
        val samples = (1..2000).map { AgentLoop.computeBackoffMs(attempt = 3, baseMs = 1000L) }
        val mean = samples.average()
        assertTrue(mean in 1700.0..2300.0) {
            "Expected mean ~2000 (±15%) from Uniform[0,4000], got $mean across ${samples.size} samples"
        }
    }

    @Test
    fun `base 200 attempt 1 stays in 0 to 200`() {
        repeat(200) {
            val d = AgentLoop.computeBackoffMs(attempt = 1, baseMs = 200L)
            assertTrue(d in 0L..200L) { "compaction base=200 attempt=1 produced $d" }
        }
    }
}

package com.workflow.orchestrator.agent.ralph

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class RalphLoopScorecardTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `fromState computes correct aggregates`() {
        val state = RalphLoopState(
            loopId = "test-123", projectPath = "/tmp", originalPrompt = "test",
            maxIterations = 10, maxCostUsd = 10.0, reviewerEnabled = true,
            phase = RalphPhase.COMPLETED, iteration = 3,
            totalCostUsd = 4.50, totalTokensUsed = 150_000,
            reviewerFeedback = null, priorAccomplishments = null,
            iterationHistory = listOf(
                RalphIterationRecord(1, "s1", 1.5, 50_000, 10_000, "IMPROVE", "fix", listOf("a.kt")),
                RalphIterationRecord(2, "s2", 1.8, 60_000, 15_000, "IMPROVE", "more", listOf("a.kt", "b.kt")),
                RalphIterationRecord(3, "s3", 1.2, 40_000, 8_000, "ACCEPT", null, listOf("a.kt"))
            ),
            autoExpandCount = 0, consecutiveImprovesWithoutProgress = 0,
            startedAt = "2026-04-03T10:00:00Z", lastIterationAt = null,
            completedAt = "2026-04-03T10:01:00Z",
            currentSessionId = "s3", allSessionIds = listOf("s1", "s2", "s3")
        )

        val scorecard = RalphLoopScorecard.fromState(state)

        assertEquals("test-123", scorecard.loopId)
        assertEquals(3, scorecard.totalIterations)
        assertEquals(4.50, scorecard.totalCostUsd, 0.001)
        assertEquals(150_000, scorecard.totalTokensUsed)
        assertEquals(33_000, scorecard.totalDurationMs)
        assertEquals(2, scorecard.totalFilesModified) // a.kt, b.kt
        assertEquals("COMPLETED", scorecard.outcome)
    }

    @Test
    fun `save and load round-trips correctly`() {
        val scorecard = RalphLoopScorecard(
            loopId = "sc-1", totalIterations = 2, totalCostUsd = 3.0,
            totalTokensUsed = 100_000, totalDurationMs = 20_000,
            totalFilesModified = 3, outcome = "COMPLETED"
        )
        RalphLoopScorecard.save(scorecard, tempDir)
        val loaded = RalphLoopScorecard.load("sc-1", tempDir)
        assertNotNull(loaded)
        assertEquals(scorecard.totalIterations, loaded!!.totalIterations)
        assertEquals(scorecard.totalCostUsd, loaded.totalCostUsd, 0.001)
    }

    @Test
    fun `load returns null for missing file`() {
        assertNull(RalphLoopScorecard.load("nonexistent", tempDir))
    }
}

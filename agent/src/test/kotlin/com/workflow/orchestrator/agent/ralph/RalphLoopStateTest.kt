package com.workflow.orchestrator.agent.ralph

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class RalphLoopStateTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `save and load round-trips state correctly`() {
        val state = RalphLoopState(
            loopId = "test-123",
            projectPath = "/tmp/project",
            originalPrompt = "Build a REST API",
            maxIterations = 10,
            maxCostUsd = 10.0,
            reviewerEnabled = true,
            phase = RalphPhase.EXECUTING,
            iteration = 3,
            totalCostUsd = 4.50,
            totalTokensUsed = 150_000,
            reviewerFeedback = "Add error handling",
            priorAccomplishments = "Created endpoints for /users and /tasks",
            iterationHistory = listOf(
                RalphIterationRecord(
                    iteration = 1, sessionId = "sess-1", costUsd = 1.50,
                    tokensUsed = 50_000, durationMs = 30_000,
                    reviewerVerdict = "IMPROVE", reviewerFeedback = "Missing validation",
                    filesChanged = listOf("src/UserController.kt")
                )
            ),
            autoExpandCount = 0,
            consecutiveImprovesWithoutProgress = 0,
            startedAt = "2026-04-03T10:00:00Z",
            lastIterationAt = "2026-04-03T10:05:00Z",
            completedAt = null,
            currentSessionId = "sess-3",
            allSessionIds = listOf("sess-1", "sess-2", "sess-3")
        )
        RalphLoopState.save(state, tempDir)
        val loaded = RalphLoopState.load(tempDir)
        assertNotNull(loaded)
        assertEquals(state.loopId, loaded!!.loopId)
        assertEquals(state.originalPrompt, loaded.originalPrompt)
        assertEquals(state.phase, loaded.phase)
        assertEquals(state.iteration, loaded.iteration)
        assertEquals(state.totalCostUsd, loaded.totalCostUsd, 0.001)
        assertEquals(state.reviewerFeedback, loaded.reviewerFeedback)
        assertEquals(1, loaded.iterationHistory.size)
        assertEquals("IMPROVE", loaded.iterationHistory[0].reviewerVerdict)
    }

    @Test
    fun `load returns null for missing file`() {
        assertNull(RalphLoopState.load(tempDir))
    }

    @Test
    fun `delete removes state file`() {
        val state = createMinimalState()
        RalphLoopState.save(state, tempDir)
        assertTrue(RalphLoopState.load(tempDir) != null)
        RalphLoopState.delete(tempDir)
        assertNull(RalphLoopState.load(tempDir))
    }

    @Test
    fun `all phases serialize correctly`() {
        for (phase in RalphPhase.entries) {
            val state = createMinimalState().copy(phase = phase)
            RalphLoopState.save(state, tempDir)
            val loaded = RalphLoopState.load(tempDir)
            assertEquals(phase, loaded?.phase)
        }
    }

    @Test
    fun `isTerminal returns true for terminal phases`() {
        assertTrue(RalphPhase.COMPLETED.isTerminal)
        assertTrue(RalphPhase.FORCE_COMPLETED.isTerminal)
        assertTrue(RalphPhase.CANCELLED.isTerminal)
        assertFalse(RalphPhase.EXECUTING.isTerminal)
        assertFalse(RalphPhase.REVIEWING.isTerminal)
        assertFalse(RalphPhase.INTERRUPTED.isTerminal)
    }

    private fun createMinimalState() = RalphLoopState(
        loopId = "min-123", projectPath = "/tmp", originalPrompt = "test",
        maxIterations = 5, maxCostUsd = 5.0, reviewerEnabled = false,
        phase = RalphPhase.EXECUTING, iteration = 1, totalCostUsd = 0.0,
        totalTokensUsed = 0, reviewerFeedback = null, priorAccomplishments = null,
        iterationHistory = emptyList(), autoExpandCount = 0,
        consecutiveImprovesWithoutProgress = 0, startedAt = "2026-04-03T10:00:00Z",
        lastIterationAt = null, completedAt = null, currentSessionId = null,
        allSessionIds = emptyList()
    )
}

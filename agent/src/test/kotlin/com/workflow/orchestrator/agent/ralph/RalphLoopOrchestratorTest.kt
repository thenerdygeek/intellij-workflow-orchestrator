package com.workflow.orchestrator.agent.ralph

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class RalphLoopOrchestratorTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var orchestrator: RalphLoopOrchestrator

    @BeforeEach
    fun setUp() {
        orchestrator = RalphLoopOrchestrator(ralphDir = tempDir)
    }

    @Test
    fun `startLoop creates state with correct defaults`() {
        val state = orchestrator.startLoop("Build a REST API", RalphLoopConfig())

        assertEquals("Build a REST API", state.originalPrompt)
        assertEquals(RalphPhase.EXECUTING, state.phase)
        assertEquals(1, state.iteration)
        assertEquals(10, state.maxIterations)
        assertEquals(10.0, state.maxCostUsd, 0.001)
        assertTrue(state.reviewerEnabled)
        assertEquals(0.0, state.totalCostUsd, 0.001)
        assertTrue(state.iterationHistory.isEmpty())
    }

    @Test
    fun `startLoop persists state to disk`() {
        val state = orchestrator.startLoop("test", RalphLoopConfig())
        val loaded = RalphLoopState.load(File(tempDir, state.loopId))
        assertNotNull(loaded)
        assertEquals(state.loopId, loaded!!.loopId)
    }

    @Test
    fun `getCurrentState returns null when no loop active`() {
        assertNull(orchestrator.getCurrentState())
    }

    @Test
    fun `getCurrentState returns active state`() {
        orchestrator.startLoop("test", RalphLoopConfig())
        assertNotNull(orchestrator.getCurrentState())
    }

    @Test
    fun `cancel sets phase to CANCELLED and returns final state`() {
        orchestrator.startLoop("test", RalphLoopConfig())
        val finalState = orchestrator.cancel()
        assertNotNull(finalState)
        assertEquals(RalphPhase.CANCELLED, finalState!!.phase)
        assertNotNull(finalState.completedAt)
    }

    @Test
    fun `cancel returns null when no active loop`() {
        assertNull(orchestrator.cancel())
    }

    @Test
    fun `onIterationCompleted returns ForcedCompletion when budget exhausted`() {
        orchestrator.startLoop("test", RalphLoopConfig(maxCostUsd = 5.0))
        val decision = orchestrator.onIterationCompleted(
            costUsd = 5.0, tokensUsed = 100_000, durationMs = 30_000,
            filesChanged = listOf("file.kt"), completionSummary = "Done", sessionId = "sess-1"
        )
        assertTrue(decision is RalphLoopDecision.ForcedCompletion)
        assertTrue((decision as RalphLoopDecision.ForcedCompletion).reason.contains("Budget"))
    }

    @Test
    fun `onIterationCompleted returns ForcedCompletion when max iterations reached without progress`() {
        orchestrator.startLoop("test", RalphLoopConfig(maxIterations = 1, reviewerEnabled = false))
        val decision = orchestrator.onIterationCompleted(
            costUsd = 1.0, tokensUsed = 50_000, durationMs = 10_000,
            filesChanged = emptyList(),
            completionSummary = "Done", sessionId = "sess-1"
        )
        assertTrue(decision is RalphLoopDecision.ForcedCompletion)
    }

    @Test
    fun `onIterationCompleted continues when reviewer disabled and under limits`() {
        orchestrator.startLoop("test", RalphLoopConfig(maxIterations = 5, reviewerEnabled = false))
        val decision = orchestrator.onIterationCompleted(
            costUsd = 1.0, tokensUsed = 50_000, durationMs = 10_000,
            filesChanged = listOf("file.kt"), completionSummary = "Done", sessionId = "sess-1"
        )
        assertTrue(decision is RalphLoopDecision.Continue)
    }

    @Test
    fun `auto-expand extends maxIterations when files changed at limit`() {
        orchestrator.startLoop("test", RalphLoopConfig(maxIterations = 1, reviewerEnabled = false))
        val decision = orchestrator.onIterationCompleted(
            costUsd = 1.0, tokensUsed = 50_000, durationMs = 10_000,
            filesChanged = listOf("file.kt"),
            completionSummary = "Done", sessionId = "sess-1"
        )
        assertTrue(decision is RalphLoopDecision.Continue)
        assertEquals(6, orchestrator.getCurrentState()!!.maxIterations) // 1 + 5
        assertEquals(1, orchestrator.getCurrentState()!!.autoExpandCount)
    }

    @Test
    fun `auto-expand capped at 3 expansions`() {
        orchestrator.startLoop("test", RalphLoopConfig(maxIterations = 1, reviewerEnabled = false))
        // Expand 3 times
        repeat(3) {
            orchestrator.onIterationCompleted(
                costUsd = 0.5, tokensUsed = 10_000, durationMs = 5_000,
                filesChanged = listOf("file.kt"), completionSummary = "Done", sessionId = "sess-$it"
            )
            val current = orchestrator.getCurrentState()!!
            orchestrator.forceIteration(current.maxIterations)
        }
        // 4th time — should NOT auto-expand
        val decision = orchestrator.onIterationCompleted(
            costUsd = 0.5, tokensUsed = 10_000, durationMs = 5_000,
            filesChanged = listOf("file.kt"), completionSummary = "Done", sessionId = "sess-final"
        )
        assertTrue(decision is RalphLoopDecision.ForcedCompletion)
    }

    @Test
    fun `buildIterationContext includes original prompt and feedback`() {
        orchestrator.startLoop("Build a REST API", RalphLoopConfig())
        orchestrator.setReviewerFeedback("Add validation", "Created endpoints")
        val context = orchestrator.buildIterationContext()

        assertTrue(context.contains("<ralph_iteration>"))
        assertTrue(context.contains("Build a REST API"))
        assertTrue(context.contains("Add validation"))
        assertTrue(context.contains("Created endpoints"))
        assertTrue(context.contains("</ralph_iteration>"))
    }

    @Test
    fun `buildIterationContext omits feedback on first iteration`() {
        orchestrator.startLoop("Fix bug", RalphLoopConfig())
        val context = orchestrator.buildIterationContext()

        assertTrue(context.contains("Fix bug"))
        assertFalse(context.contains("Reviewer Feedback"))
    }

    @Test
    fun `onReviewerResult ACCEPT completes the loop`() {
        orchestrator.startLoop("test", RalphLoopConfig())
        orchestrator.onIterationCompleted(
            costUsd = 1.0, tokensUsed = 50_000, durationMs = 10_000,
            filesChanged = listOf("file.kt"), completionSummary = "Done", sessionId = "sess-1"
        )
        val decision = orchestrator.onReviewerResult(
            ReviewResult(ReviewVerdict.ACCEPT, null),
            reviewerCostUsd = 0.30
        )
        assertTrue(decision is RalphLoopDecision.Completed)
        assertEquals(RalphPhase.COMPLETED, orchestrator.getCurrentState()!!.phase)
    }

    @Test
    fun `onReviewerResult IMPROVE continues with feedback`() {
        orchestrator.startLoop("test", RalphLoopConfig())
        orchestrator.onIterationCompleted(
            costUsd = 1.0, tokensUsed = 50_000, durationMs = 10_000,
            filesChanged = listOf("file.kt"), completionSummary = "Done", sessionId = "sess-1"
        )
        val decision = orchestrator.onReviewerResult(
            ReviewResult(ReviewVerdict.IMPROVE, "Add tests"),
            reviewerCostUsd = 0.30
        )
        assertTrue(decision is RalphLoopDecision.Continue)
        assertEquals("Add tests", orchestrator.getCurrentState()!!.reviewerFeedback)
        assertEquals(2, orchestrator.getCurrentState()!!.iteration)
    }

    @Test
    fun `3 consecutive IMPROVEs without file changes forces completion`() {
        orchestrator.startLoop("test", RalphLoopConfig(maxIterations = 10))
        repeat(3) {
            orchestrator.onIterationCompleted(
                costUsd = 1.0, tokensUsed = 50_000, durationMs = 10_000,
                filesChanged = emptyList(),
                completionSummary = "Done", sessionId = "sess-$it"
            )
            orchestrator.onReviewerResult(
                ReviewResult(ReviewVerdict.IMPROVE, "Try harder"),
                reviewerCostUsd = 0.30
            )
        }
        assertEquals(RalphPhase.FORCE_COMPLETED, orchestrator.getCurrentState()!!.phase)
    }

    @Test
    fun `iteration history records all iterations`() {
        orchestrator.startLoop("test", RalphLoopConfig(maxIterations = 5, reviewerEnabled = false))
        orchestrator.onIterationCompleted(
            costUsd = 1.0, tokensUsed = 50_000, durationMs = 10_000,
            filesChanged = listOf("a.kt"), completionSummary = "First", sessionId = "s1"
        )
        orchestrator.onIterationCompleted(
            costUsd = 0.8, tokensUsed = 40_000, durationMs = 8_000,
            filesChanged = listOf("b.kt"), completionSummary = "Second", sessionId = "s2"
        )
        val state = orchestrator.getCurrentState()!!
        assertEquals(2, state.iterationHistory.size)
        assertEquals("s1", state.iterationHistory[0].sessionId)
        assertEquals("s2", state.iterationHistory[1].sessionId)
        assertEquals(listOf("s1", "s2"), state.allSessionIds.take(2))
    }

    @Test
    fun `full lifecycle — start, iterate with review, complete`() {
        orchestrator.startLoop("Build API", RalphLoopConfig(maxIterations = 10))

        // Iteration 1
        orchestrator.onIterationCompleted(
            costUsd = 1.5, tokensUsed = 50_000, durationMs = 10_000,
            filesChanged = listOf("UserController.kt"), completionSummary = "Created /users",
            sessionId = "sess-1"
        )
        val d1 = orchestrator.onReviewerResult(
            ReviewResult(ReviewVerdict.IMPROVE, "Add validation"), 0.30
        )
        assertTrue(d1 is RalphLoopDecision.Continue)

        // Iteration 2
        orchestrator.onIterationCompleted(
            costUsd = 1.2, tokensUsed = 40_000, durationMs = 8_000,
            filesChanged = listOf("UserController.kt"), completionSummary = "Added validation",
            sessionId = "sess-2"
        )
        val d2 = orchestrator.onReviewerResult(
            ReviewResult(ReviewVerdict.ACCEPT, null), 0.20
        )
        assertTrue(d2 is RalphLoopDecision.Completed)
        assertEquals(RalphPhase.COMPLETED, orchestrator.getCurrentState()!!.phase)
        assertEquals(2, (d2 as RalphLoopDecision.Completed).iterations)
        assertTrue(d2.totalCost > 3.0)
    }
}

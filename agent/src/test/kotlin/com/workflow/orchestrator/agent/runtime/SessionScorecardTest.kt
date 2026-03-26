package com.workflow.orchestrator.agent.runtime

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SessionScorecardTest {

    @Test
    fun `compute produces scorecard with all fields from metrics`() {
        val metrics = AgentMetrics()
        metrics.recordToolCall("read_file", 50, true, 100)
        metrics.recordToolCall("edit_file", 100, true, 200)
        metrics.recordToolCall("read_file", 30, false, 50)
        metrics.turnCount = 5
        metrics.compressionCount = 1
        metrics.approvalCount = 2
        metrics.subagentCount = 1

        val selfCorrectionGate = SelfCorrectionGate(maxRetriesPerFile = 3)
        selfCorrectionGate.recordEdit("src/Foo.kt")
        selfCorrectionGate.recordVerification("src/Foo.kt", true)
        selfCorrectionGate.recordEdit("src/Bar.kt")
        selfCorrectionGate.recordVerification("src/Bar.kt", false, "compile error")

        val scorecard = SessionScorecard.compute(
            sessionId = "test-123",
            taskDescription = "Fix the bug",
            status = "completed",
            agentMetrics = metrics,
            selfCorrectionGate = selfCorrectionGate,
            planStepsTotal = 3,
            planStepsCompleted = 2,
            durationMs = 10_000,
            totalInputTokens = 50_000,
            totalOutputTokens = 5_000,
            hallucinationFlags = 1,
            credentialLeakAttempts = 0,
            doomLoopTriggers = 2,
            guardrailHits = 1
        )

        assertEquals("test-123", scorecard.sessionId)
        assertEquals("Fix the bug", scorecard.taskDescription)
        assertEquals("completed", scorecard.completionStatus)

        // Metrics
        assertEquals(5, scorecard.metrics.totalIterations)
        assertEquals(3, scorecard.metrics.toolCallCount) // 2 read_file + 1 edit_file
        assertEquals(2, scorecard.metrics.uniqueToolsUsed) // read_file, edit_file
        assertEquals(1, scorecard.metrics.errorCount) // 1 failed read_file
        assertEquals(1, scorecard.metrics.compressionCount)
        assertEquals(50_000L, scorecard.metrics.totalInputTokens)
        assertEquals(5_000L, scorecard.metrics.totalOutputTokens)
        assertTrue(scorecard.metrics.estimatedCostUsd > 0)
        assertEquals(3, scorecard.metrics.planStepsTotal)
        assertEquals(2, scorecard.metrics.planStepsCompleted)
        assertEquals(1, scorecard.metrics.selfCorrectionAttempts) // Bar.kt had 1 retry
        assertEquals(0, scorecard.metrics.selfCorrectionSuccesses) // Bar.kt not verified
        assertEquals(2, scorecard.metrics.approvalCount)
        assertEquals(1, scorecard.metrics.subagentCount)
        assertEquals(10_000L, scorecard.metrics.durationMs)

        // Quality signals
        assertEquals(1, scorecard.qualitySignals.hallucinationFlags)
        assertEquals(0, scorecard.qualitySignals.credentialLeakAttempts)
        assertEquals(2, scorecard.qualitySignals.doomLoopTriggers)
        assertEquals(0, scorecard.qualitySignals.circuitBreakerTrips)
        assertEquals(1, scorecard.qualitySignals.guardrailHits)
        assertEquals(2, scorecard.qualitySignals.filesEditedCount) // Foo.kt, Bar.kt
        assertEquals(1, scorecard.qualitySignals.filesVerifiedCount) // Foo.kt
        assertEquals(0, scorecard.qualitySignals.filesExhaustedCount)
    }

    @Test
    fun `compute handles null selfCorrectionGate`() {
        val metrics = AgentMetrics()
        metrics.turnCount = 3

        val scorecard = SessionScorecard.compute(
            sessionId = "s1",
            taskDescription = "Task",
            status = "failed",
            agentMetrics = metrics,
            selfCorrectionGate = null,
            durationMs = 5_000
        )

        assertEquals("failed", scorecard.completionStatus)
        assertEquals(3, scorecard.metrics.totalIterations)
        assertEquals(0, scorecard.metrics.selfCorrectionAttempts)
        assertEquals(0, scorecard.qualitySignals.filesEditedCount)
        assertEquals(0, scorecard.qualitySignals.filesVerifiedCount)
        assertEquals(0, scorecard.qualitySignals.filesExhaustedCount)
    }

    @Test
    fun `compute detects circuit breaker trips`() {
        val metrics = AgentMetrics()
        // Trigger circuit breaker on a tool (5 consecutive failures)
        repeat(AgentMetrics.CIRCUIT_BREAKER_THRESHOLD) {
            metrics.recordToolCall("broken_tool", 10, false, 10)
        }

        val scorecard = SessionScorecard.compute(
            sessionId = "s2",
            taskDescription = "Task",
            status = "failed",
            agentMetrics = metrics,
            selfCorrectionGate = null,
            durationMs = 1_000
        )

        assertEquals(1, scorecard.qualitySignals.circuitBreakerTrips)
        assertEquals(AgentMetrics.CIRCUIT_BREAKER_THRESHOLD, scorecard.metrics.errorCount)
    }

    @Test
    fun `compute tracks exhausted files`() {
        val gate = SelfCorrectionGate(maxRetriesPerFile = 2)
        gate.recordEdit("src/Broken.kt")
        gate.recordVerification("src/Broken.kt", false, "error 1")
        gate.recordVerification("src/Broken.kt", false, "error 2")

        val metrics = AgentMetrics()
        val scorecard = SessionScorecard.compute(
            sessionId = "s3",
            taskDescription = "Task",
            status = "completed",
            agentMetrics = metrics,
            selfCorrectionGate = gate,
            durationMs = 2_000
        )

        assertEquals(1, scorecard.qualitySignals.filesExhaustedCount)
        assertEquals(0, scorecard.qualitySignals.filesVerifiedCount)
        assertEquals(1, scorecard.qualitySignals.filesEditedCount)
    }

    @Test
    fun `compute counts self-correction successes`() {
        val gate = SelfCorrectionGate(maxRetriesPerFile = 3)
        gate.recordEdit("src/A.kt")
        gate.recordVerification("src/A.kt", false, "error")
        gate.recordVerification("src/A.kt", true)  // verified after retry

        val metrics = AgentMetrics()
        val scorecard = SessionScorecard.compute(
            sessionId = "s4",
            taskDescription = "Task",
            status = "completed",
            agentMetrics = metrics,
            selfCorrectionGate = gate,
            durationMs = 3_000
        )

        assertEquals(1, scorecard.metrics.selfCorrectionAttempts)
        assertEquals(1, scorecard.metrics.selfCorrectionSuccesses)
    }

    @Test
    fun `computeEstimatedCost returns correct cost`() {
        // $3/1M input + $15/1M output
        val cost = SessionScorecard.computeEstimatedCost(1_000_000, 100_000)
        assertEquals(3.0 + 1.5, cost, 0.001)
    }

    @Test
    fun `computeEstimatedCost returns zero for zero tokens`() {
        assertEquals(0.0, SessionScorecard.computeEstimatedCost(0, 0), 0.0001)
    }

    @Test
    fun `task description truncated to 500 chars`() {
        val longTask = "A".repeat(1000)
        val metrics = AgentMetrics()
        val scorecard = SessionScorecard.compute(
            sessionId = "s5",
            taskDescription = longTask,
            status = "completed",
            agentMetrics = metrics,
            selfCorrectionGate = null,
            durationMs = 1_000
        )

        assertEquals(500, scorecard.taskDescription.length)
    }

    @Test
    fun `timestamp is populated automatically`() {
        val before = System.currentTimeMillis()
        val metrics = AgentMetrics()
        val scorecard = SessionScorecard.compute(
            sessionId = "s6",
            taskDescription = "Test",
            status = "completed",
            agentMetrics = metrics,
            selfCorrectionGate = null,
            durationMs = 100
        )
        val after = System.currentTimeMillis()

        assertTrue(scorecard.timestamp in before..after)
    }
}

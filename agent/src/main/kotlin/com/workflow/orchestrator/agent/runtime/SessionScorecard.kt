package com.workflow.orchestrator.agent.runtime

import kotlinx.serialization.Serializable

/**
 * End-of-session quality scorecard capturing metrics and quality signals.
 *
 * Computed at session end (completion, failure, cancellation, or rotation)
 * from [AgentMetrics], [SelfCorrectionGate], [PlanManager], and event logs.
 *
 * Persisted by [com.workflow.orchestrator.agent.service.MetricsStore] for trend analysis.
 */
@Serializable
data class SessionScorecard(
    val sessionId: String,
    val taskDescription: String,
    val completionStatus: String, // "completed", "failed", "cancelled", "rotated"
    val timestamp: Long,
    val metrics: SessionScorecardMetrics,
    val qualitySignals: QualitySignals
) {
    companion object {
        // Claude Sonnet pricing (rough baseline for cost estimation)
        // $3 per 1M input tokens, $15 per 1M output tokens
        private const val INPUT_COST_PER_TOKEN = 3.0 / 1_000_000
        private const val OUTPUT_COST_PER_TOKEN = 15.0 / 1_000_000

        /**
         * Compute a scorecard from session runtime data.
         *
         * @param sessionId Unique session identifier
         * @param taskDescription The user's task description (truncated)
         * @param status Completion status: "completed", "failed", "cancelled", "rotated"
         * @param agentMetrics Per-session metrics collector
         * @param selfCorrectionGate Edit/verify tracker (nullable if not used)
         * @param planStepsTotal Total plan steps (0 if no plan)
         * @param planStepsCompleted Completed plan steps (0 if no plan)
         * @param durationMs Session wall-clock duration
         * @param totalInputTokens Cumulative input tokens across all LLM calls
         * @param totalOutputTokens Cumulative output tokens across all LLM calls
         * @param hallucinationFlags Count of hallucination flags from OutputValidator
         * @param credentialLeakAttempts Count of credential leak detections from CredentialRedactor
         * @param doomLoopTriggers Count of doom loop detections from LoopGuard
         * @param guardrailHits Count of guardrail violations from GuardrailStore
         */
        fun compute(
            sessionId: String,
            taskDescription: String,
            status: String,
            agentMetrics: AgentMetrics,
            selfCorrectionGate: SelfCorrectionGate?,
            planStepsTotal: Int = 0,
            planStepsCompleted: Int = 0,
            durationMs: Long,
            totalInputTokens: Long = 0,
            totalOutputTokens: Long = 0,
            hallucinationFlags: Int = 0,
            credentialLeakAttempts: Int = 0,
            doomLoopTriggers: Int = 0,
            guardrailHits: Int = 0
        ): SessionScorecard {
            val snapshot = agentMetrics.snapshot()
            val fileStates = selfCorrectionGate?.getFileStates() ?: emptyMap()
            val exhaustedFiles = selfCorrectionGate?.getExhaustedFiles() ?: emptySet()

            val totalToolCalls = snapshot.toolCalls.values.sumOf { it.callCount }
            val uniqueTools = snapshot.toolCalls.size
            val totalErrors = snapshot.toolCalls.values.sumOf { it.errorCount }

            // Count self-correction attempts and successes from file states
            val selfCorrectionAttempts = fileStates.values.sumOf { it.retryCount }
            val selfCorrectionSuccesses = fileStates.values.count { it.verified && it.retryCount > 0 }

            // Circuit breaker trips: count tools that hit the threshold
            val circuitBreakerTrips = snapshot.toolCalls.values.count {
                it.consecutiveErrors >= AgentMetrics.CIRCUIT_BREAKER_THRESHOLD
            }

            val estimatedCost = computeEstimatedCost(totalInputTokens, totalOutputTokens)

            return SessionScorecard(
                sessionId = sessionId,
                taskDescription = taskDescription.take(500),
                completionStatus = status,
                timestamp = System.currentTimeMillis(),
                metrics = SessionScorecardMetrics(
                    totalIterations = snapshot.turnCount,
                    toolCallCount = totalToolCalls,
                    uniqueToolsUsed = uniqueTools,
                    errorCount = totalErrors,
                    compressionCount = snapshot.compressionCount,
                    totalInputTokens = totalInputTokens,
                    totalOutputTokens = totalOutputTokens,
                    estimatedCostUsd = estimatedCost,
                    planStepsTotal = planStepsTotal,
                    planStepsCompleted = planStepsCompleted,
                    selfCorrectionAttempts = selfCorrectionAttempts,
                    selfCorrectionSuccesses = selfCorrectionSuccesses,
                    approvalCount = snapshot.approvalCount,
                    subagentCount = snapshot.subagentCount,
                    durationMs = durationMs
                ),
                qualitySignals = QualitySignals(
                    hallucinationFlags = hallucinationFlags,
                    credentialLeakAttempts = credentialLeakAttempts,
                    doomLoopTriggers = doomLoopTriggers,
                    circuitBreakerTrips = circuitBreakerTrips,
                    guardrailHits = guardrailHits,
                    filesEditedCount = fileStates.size,
                    filesVerifiedCount = fileStates.values.count { it.verified },
                    filesExhaustedCount = exhaustedFiles.size
                )
            )
        }

        /**
         * Estimate cost based on token counts using Claude Sonnet pricing as baseline.
         *
         * @param inputTokens Total input tokens across all LLM calls
         * @param outputTokens Total output tokens across all LLM calls
         * @return Estimated cost in USD
         */
        fun computeEstimatedCost(inputTokens: Long, outputTokens: Long): Double {
            return (inputTokens * INPUT_COST_PER_TOKEN) + (outputTokens * OUTPUT_COST_PER_TOKEN)
        }
    }
}

/**
 * Quantitative metrics from the session execution.
 */
@Serializable
data class SessionScorecardMetrics(
    val totalIterations: Int,
    val toolCallCount: Int,
    val uniqueToolsUsed: Int,
    val errorCount: Int,
    val compressionCount: Int,
    val totalInputTokens: Long,
    val totalOutputTokens: Long,
    val estimatedCostUsd: Double,
    val planStepsTotal: Int,
    val planStepsCompleted: Int,
    val selfCorrectionAttempts: Int,
    val selfCorrectionSuccesses: Int,
    val approvalCount: Int,
    val subagentCount: Int,
    val durationMs: Long
)

/**
 * Quality signals indicating potential issues during the session.
 * Higher values indicate more problems.
 */
@Serializable
data class QualitySignals(
    val hallucinationFlags: Int,
    val credentialLeakAttempts: Int,
    val doomLoopTriggers: Int,
    val circuitBreakerTrips: Int,
    val guardrailHits: Int,
    val filesEditedCount: Int,
    val filesVerifiedCount: Int,
    val filesExhaustedCount: Int
)

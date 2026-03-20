package com.workflow.orchestrator.agent.runtime

import com.intellij.openapi.diagnostic.Logger
import com.workflow.orchestrator.agent.context.ContextManager

/**
 * Monitors token usage during the ReAct loop and signals when action is needed.
 *
 * Thresholds (relative to effectiveBudget — already accounting for reserved tokens like
 * tool definitions, system prompt overhead, and safety buffer):
 * - OK: under 40% — proceed normally
 * - COMPRESS: 40%-60% — trigger context compression
 * - NUDGE: 60%-75% — inject nudge into system prompt suggesting delegation or wrap-up
 * - STRONG_NUDGE: 75%-90% — inject strong nudge urging immediate delegation or conclusion
 * - TERMINATE: over 90% — hard stop, context is exhausted
 */
class BudgetEnforcer(
    private val contextManager: ContextManager,
    private val effectiveBudget: Int = com.workflow.orchestrator.agent.settings.AgentSettings.DEFAULTS.maxInputTokens
) {
    companion object {
        private val LOG = Logger.getInstance(BudgetEnforcer::class.java)
        private const val COMPRESSION_RATIO = 0.40
        private const val NUDGE_RATIO = 0.60
        private const val STRONG_NUDGE_RATIO = 0.75
        private const val TERMINATE_RATIO = 0.90
    }

    private val compressionThreshold = (effectiveBudget * COMPRESSION_RATIO).toInt()
    private val nudgeThreshold = (effectiveBudget * NUDGE_RATIO).toInt()
    private val strongNudgeThreshold = (effectiveBudget * STRONG_NUDGE_RATIO).toInt()
    private val terminateThreshold = (effectiveBudget * TERMINATE_RATIO).toInt()

    /**
     * Check current token usage and return the appropriate budget status.
     */
    fun check(): BudgetStatus {
        val used = contextManager.currentTokens
        return when {
            used < compressionThreshold -> {
                BudgetStatus.OK
            }
            used < nudgeThreshold -> {
                LOG.info("BudgetEnforcer: approaching budget limit ($used/$effectiveBudget tokens, ${(used * 100) / effectiveBudget}%). Compression recommended.")
                BudgetStatus.COMPRESS
            }
            used < strongNudgeThreshold -> {
                LOG.warn("BudgetEnforcer: budget elevated ($used/$effectiveBudget tokens, ${(used * 100) / effectiveBudget}%). Consider delegating remaining work or wrapping up.")
                BudgetStatus.NUDGE
            }
            used < terminateThreshold -> {
                LOG.warn("BudgetEnforcer: budget critical ($used/$effectiveBudget tokens, ${(used * 100) / effectiveBudget}%). Strongly recommend delegating or concluding immediately.")
                BudgetStatus.STRONG_NUDGE
            }
            else -> {
                LOG.warn("BudgetEnforcer: budget exhausted ($used/$effectiveBudget tokens, ${(used * 100) / effectiveBudget}%). Terminating session.")
                BudgetStatus.TERMINATE
            }
        }
    }

    /**
     * Get the current utilization as a percentage (0-100).
     */
    fun utilizationPercent(): Int {
        return (contextManager.currentTokens * 100) / effectiveBudget
    }

    enum class BudgetStatus {
        /** Under compression threshold — proceed normally. */
        OK,
        /** Between compression and nudge thresholds — compress context. */
        COMPRESS,
        /** Between nudge and strong nudge thresholds — suggest delegation or wrap-up. */
        NUDGE,
        /** Between strong nudge and terminate thresholds — urgently recommend delegation or conclusion. */
        STRONG_NUDGE,
        /** Over terminate threshold — hard stop, context exhausted. */
        TERMINATE
    }
}

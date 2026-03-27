package com.workflow.orchestrator.agent.runtime

import com.intellij.openapi.diagnostic.Logger
import com.workflow.orchestrator.agent.context.ContextManager

/**
 * Monitors token usage during the ReAct loop and signals when action is needed.
 *
 * Three-tier budget system (Cline-inspired, single compression threshold):
 * - OK: under 80% — proceed normally
 * - COMPRESS: 80%-97% — trigger LLM summary + sliding window compression
 * - TERMINATE: over 97% — hard stop, context rotation or fail
 */
class BudgetEnforcer(
    private val contextManager: ContextManager,
    private val effectiveBudget: Int = com.workflow.orchestrator.agent.settings.AgentSettings.DEFAULTS.maxInputTokens
) {
    companion object {
        private val LOG = Logger.getInstance(BudgetEnforcer::class.java)
        private const val COMPRESSION_RATIO = 0.80
        private const val TERMINATE_RATIO = 0.97
    }

    private val compressionThreshold = (effectiveBudget * COMPRESSION_RATIO).toInt()
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
            used < terminateThreshold -> {
                LOG.info("BudgetEnforcer: compression needed ($used/$effectiveBudget tokens, ${(used * 100) / effectiveBudget}%).")
                BudgetStatus.COMPRESS
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
        /** Between compression and terminate thresholds — compress context. */
        COMPRESS,
        /** Over terminate threshold — hard stop, context exhausted. */
        TERMINATE
    }
}

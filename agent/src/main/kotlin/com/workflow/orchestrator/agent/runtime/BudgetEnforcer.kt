package com.workflow.orchestrator.agent.runtime

import com.intellij.openapi.diagnostic.Logger
import com.workflow.orchestrator.agent.context.ContextManager

/**
 * Monitors token usage during the ReAct loop and signals when action is needed.
 *
 * Thresholds (relative to maxInputTokens):
 * - OK: under 40% — proceed normally
 * - COMPRESS: 40%-60% — trigger context compression
 * - ESCALATE: over 60% — context too large for single agent, escalate to orchestrated mode
 */
class BudgetEnforcer(
    private val contextManager: ContextManager,
    private val maxInputTokens: Int = 150_000
) {
    companion object {
        private val LOG = Logger.getInstance(BudgetEnforcer::class.java)
        private const val COMPRESSION_RATIO = 0.40
        private const val CRITICAL_RATIO = 0.60
    }

    private val compressionThreshold = (maxInputTokens * COMPRESSION_RATIO).toInt()
    private val criticalThreshold = (maxInputTokens * CRITICAL_RATIO).toInt()

    /**
     * Check current token usage and return the appropriate budget status.
     */
    fun check(): BudgetStatus {
        val used = contextManager.currentTokens
        return when {
            used < compressionThreshold -> {
                BudgetStatus.OK
            }
            used < criticalThreshold -> {
                LOG.info("BudgetEnforcer: approaching budget limit ($used/$maxInputTokens tokens, ${(used * 100) / maxInputTokens}%). Compression recommended.")
                BudgetStatus.COMPRESS
            }
            else -> {
                LOG.warn("BudgetEnforcer: budget critical ($used/$maxInputTokens tokens, ${(used * 100) / maxInputTokens}%). Escalation needed.")
                BudgetStatus.ESCALATE
            }
        }
    }

    /**
     * Get the current utilization as a percentage (0-100).
     */
    fun utilizationPercent(): Int {
        return (contextManager.currentTokens * 100) / maxInputTokens
    }

    enum class BudgetStatus {
        /** Under compression threshold — proceed normally. */
        OK,
        /** Between compression and critical thresholds — compress context. */
        COMPRESS,
        /** Over critical threshold — escalate to orchestrated mode. */
        ESCALATE
    }
}

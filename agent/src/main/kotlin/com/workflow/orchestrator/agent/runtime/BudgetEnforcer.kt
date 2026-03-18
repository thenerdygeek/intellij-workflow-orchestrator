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
 * - ESCALATE: over 60% — context too large for single agent, escalate to orchestrated mode
 */
class BudgetEnforcer(
    private val contextManager: ContextManager,
    private val effectiveBudget: Int = 150_000
) {
    companion object {
        private val LOG = Logger.getInstance(BudgetEnforcer::class.java)
        private const val COMPRESSION_RATIO = 0.40
        private const val CRITICAL_RATIO = 0.60
    }

    private val compressionThreshold = (effectiveBudget * COMPRESSION_RATIO).toInt()
    private val criticalThreshold = (effectiveBudget * CRITICAL_RATIO).toInt()

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
                LOG.info("BudgetEnforcer: approaching budget limit ($used/$effectiveBudget tokens, ${(used * 100) / effectiveBudget}%). Compression recommended.")
                BudgetStatus.COMPRESS
            }
            else -> {
                LOG.warn("BudgetEnforcer: budget critical ($used/$effectiveBudget tokens, ${(used * 100) / effectiveBudget}%). Escalation needed.")
                BudgetStatus.ESCALATE
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
        /** Between compression and critical thresholds — compress context. */
        COMPRESS,
        /** Over critical threshold — escalate to orchestrated mode. */
        ESCALATE
    }
}

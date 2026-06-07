package com.workflow.orchestrator.agent.loop

/**
 * Pure network-error-recovery policy — the decision for whether (and with what tier chain) the
 * agent loop may perform **L2 tier escalation** (a one-way model fallback down the tier chain
 * when same-tier brain recycling is exhausted). Extracted from `AgentService.executeTask`
 * (Phase 3 cut B, incision 1) so the rule is unit-testable instead of buried in the god-function.
 *
 * Because L2 escalation can silently change the model the user picked, these rules are
 * billing-adjacent. Pinned by `NetworkRecoveryPolicyTest`.
 */
object NetworkRecoveryPolicy {

    /** The user opted out of any automatic model switching. */
    const val STRATEGY_NONE = "none"

    /** Enables L2 escalation AND compact-on-timeout-exhaustion. */
    const val STRATEGY_CONTEXT_COMPACTION = "context_compaction"

    /** Normalize the raw `AgentSettings.networkErrorStrategy`; a null setting means [STRATEGY_NONE]. */
    fun effectiveStrategy(raw: String?): String = raw ?: STRATEGY_NONE

    /**
     * The fallback chain the loop may escalate down, or null when L2 escalation must stay disabled:
     *  - [STRATEGY_NONE] → null (user opted out — never silently switch their chosen model);
     *  - a [builtChain] of ≤1 tier → null (nothing to escalate to).
     *
     * [builtChain] is the already-built ordered tier list (e.g. from `ModelCache.buildFallbackChain`).
     */
    fun fallbackChainOrNull(strategy: String, builtChain: List<String>): List<String>? = when {
        strategy == STRATEGY_NONE -> null
        builtChain.size > 1 -> builtChain
        else -> null
    }

    /** Whether the loop should compact context as the last resort on timeout exhaustion. */
    fun compactOnTimeoutExhaustion(strategy: String): Boolean = strategy == STRATEGY_CONTEXT_COMPACTION
}

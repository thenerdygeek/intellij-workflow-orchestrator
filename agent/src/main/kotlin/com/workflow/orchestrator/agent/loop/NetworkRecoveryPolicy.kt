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

    /**
     * Outcome of [resolveFallbackChain]: the chain L2 escalation may descend ([chain], null when
     * escalation stays disabled) plus [reason] for WHICH branch was taken, so the caller can emit
     * the matching log line without re-deriving the branch.
     */
    data class FallbackChainResolution(val chain: List<String>?, val reason: Reason) {
        enum class Reason {
            /** The user opted out via [STRATEGY_NONE] — never silently switch their chosen model. */
            STRATEGY_NONE,

            /** A strategy is set but the built chain has ≤1 tier — nothing to escalate to. */
            CHAIN_TOO_SHORT,

            /** A strategy is set and the built chain has ≥2 tiers — L2 escalation is enabled. */
            CHAIN_AVAILABLE,
        }
    }

    /**
     * Decide whether (and with what chain) L2 tier escalation runs, wrapping [fallbackChainOrNull]
     * with the three-way branch selection that was inline in `AgentService.executeTask` (Phase 3
     * cut B, incision 2). [buildChain] is invoked lazily — only when a strategy other than
     * [STRATEGY_NONE] is set — so the opt-out path never pays the cost of building the chain.
     */
    fun resolveFallbackChain(
        strategy: String,
        buildChain: () -> List<String>,
    ): FallbackChainResolution = when {
        strategy == STRATEGY_NONE ->
            FallbackChainResolution(null, FallbackChainResolution.Reason.STRATEGY_NONE)
        else -> {
            val resolved = fallbackChainOrNull(strategy, buildChain())
            if (resolved != null) {
                FallbackChainResolution(resolved, FallbackChainResolution.Reason.CHAIN_AVAILABLE)
            } else {
                FallbackChainResolution(null, FallbackChainResolution.Reason.CHAIN_TOO_SHORT)
            }
        }
    }
}

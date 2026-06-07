package com.workflow.orchestrator.agent.loop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Behavioral characterization of [NetworkRecoveryPolicy] — the network-error-recovery decision
 * extracted out of `AgentService.executeTask` (Phase 3 cut B, incision 1). This rule lived inline
 * in the ~1090-line god-function and so was un-unit-testable; carving the pure decision out makes
 * it directly assertable.
 *
 * The policy gates **L2 tier escalation** (one-way model fallback down the tier chain) — which can
 * silently change the user's chosen model, so the rules are billing-adjacent and worth pinning:
 *  - `networkErrorStrategy == "none"` → the user opted out → no fallback chain (null);
 *  - a built chain of ≤1 model → nothing to escalate to → null;
 *  - `"context_compaction"` additionally enables compact-on-timeout-exhaustion.
 */
class NetworkRecoveryPolicyTest {

    @Test
    fun `effectiveStrategy defaults a null setting to none`() {
        assertEquals("none", NetworkRecoveryPolicy.effectiveStrategy(null))
    }

    @Test
    fun `effectiveStrategy passes through a configured value verbatim`() {
        assertEquals("model_fallback", NetworkRecoveryPolicy.effectiveStrategy("model_fallback"))
        assertEquals("context_compaction", NetworkRecoveryPolicy.effectiveStrategy("context_compaction"))
    }

    @Test
    fun `fallbackChainOrNull returns null when the user opted out with none`() {
        assertNull(
            NetworkRecoveryPolicy.fallbackChainOrNull("none", listOf("a::1", "b::2", "c::3")),
            "strategy 'none' must disable L2 escalation even when a multi-model chain exists",
        )
    }

    @Test
    fun `fallbackChainOrNull returns the chain when a strategy is set and there are 2+ tiers`() {
        val chain = listOf("opus-thinking", "opus", "sonnet")
        assertEquals(chain, NetworkRecoveryPolicy.fallbackChainOrNull("model_fallback", chain))
    }

    @Test
    fun `fallbackChainOrNull returns null when the chain has one or zero tiers`() {
        assertNull(NetworkRecoveryPolicy.fallbackChainOrNull("model_fallback", listOf("only-one")))
        assertNull(NetworkRecoveryPolicy.fallbackChainOrNull("context_compaction", emptyList()))
    }

    @Test
    fun `compactOnTimeoutExhaustion only for the context_compaction strategy`() {
        assertTrue(NetworkRecoveryPolicy.compactOnTimeoutExhaustion("context_compaction"))
        assertFalse(NetworkRecoveryPolicy.compactOnTimeoutExhaustion("model_fallback"))
        assertFalse(NetworkRecoveryPolicy.compactOnTimeoutExhaustion("none"))
    }

    // ---- resolveFallbackChain (Phase 3 cut B, incision 2) ----
    // Wraps fallbackChainOrNull with the three-way branch selection that was inline in
    // AgentService.executeTask, returning WHICH branch was taken so the caller can emit the
    // matching log line without re-deriving the branch. The chain is built lazily so the
    // strategy='none' opt-out never pays the cost of building it (behavior preserved exactly).

    @Test
    fun `resolveFallbackChain with none returns null and STRATEGY_NONE without building the chain`() {
        var built = false
        val result = NetworkRecoveryPolicy.resolveFallbackChain("none") {
            built = true
            listOf("a::1", "b::2")
        }
        assertNull(result.chain, "strategy 'none' disables L2 escalation")
        assertEquals(NetworkRecoveryPolicy.FallbackChainResolution.Reason.STRATEGY_NONE, result.reason)
        assertFalse(built, "the chain must NOT be built when the user opted out (short-circuit preserved)")
    }

    @Test
    fun `resolveFallbackChain with a strategy and 2+ tiers returns the chain and CHAIN_AVAILABLE`() {
        val chain = listOf("opus-thinking", "opus", "sonnet")
        val result = NetworkRecoveryPolicy.resolveFallbackChain("model_fallback") { chain }
        assertEquals(chain, result.chain)
        assertEquals(NetworkRecoveryPolicy.FallbackChainResolution.Reason.CHAIN_AVAILABLE, result.reason)
    }

    @Test
    fun `resolveFallbackChain with a strategy and one or zero tiers returns null and CHAIN_TOO_SHORT`() {
        val one = NetworkRecoveryPolicy.resolveFallbackChain("model_fallback") { listOf("only-one") }
        assertNull(one.chain)
        assertEquals(NetworkRecoveryPolicy.FallbackChainResolution.Reason.CHAIN_TOO_SHORT, one.reason)

        val none = NetworkRecoveryPolicy.resolveFallbackChain("context_compaction") { emptyList() }
        assertNull(none.chain)
        assertEquals(NetworkRecoveryPolicy.FallbackChainResolution.Reason.CHAIN_TOO_SHORT, none.reason)
    }
}

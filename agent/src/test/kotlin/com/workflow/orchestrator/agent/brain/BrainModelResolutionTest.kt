package com.workflow.orchestrator.agent.brain

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Behavioral characterization of [BrainFactory.resolveModel] — the model-selection precedence
 * extracted out of `AgentService.createBrain` (Phase 3 cut D). Previously this logic was guarded
 * only by a source-text grep (`CreateBrainModelPriorityTest`) because it lived inside a
 * `@Service` god-class entangled with platform service lookups; carving the pure precedence into
 * this function makes the rule directly assertable.
 *
 * The precedence is: caller override > saved user selection > auto-picked best > factory fallback.
 * The middle two rungs encode the 2026-05-06 regression fix — the user's saved chip selection MUST
 * win over `ModelCache.pickBest()` (which prefers Opus-thinking), or a Sonnet user is silently
 * billed ~5x. `fetchBest` (the expensive `ModelCache` fetch + pickBest) must NOT run when an
 * override or a saved selection already resolves the model.
 */
class BrainModelResolutionTest {

    @Test
    fun `caller override wins and short-circuits the fetch`() = runTest {
        var fetchCalled = false
        val resolution = BrainFactory.resolveModel(
            modelOverride = "anthropic::claude-opus",
            savedModel = "anthropic::claude-sonnet",
            fetchBest = {
                fetchCalled = true
                "anthropic::auto-best"
            },
        )
        assertEquals(BrainFactory.ModelResolution.Use("anthropic::claude-opus"), resolution)
        assertFalse(fetchCalled, "override must short-circuit before the expensive fetch")
    }

    @Test
    fun `saved selection wins over auto-pick when no override`() = runTest {
        var fetchCalled = false
        val resolution = BrainFactory.resolveModel(
            modelOverride = null,
            savedModel = "anthropic::claude-sonnet",
            fetchBest = {
                fetchCalled = true
                "anthropic::claude-opus-thinking"
            },
        )
        assertEquals(
            BrainFactory.ModelResolution.Use("anthropic::claude-sonnet"),
            resolution,
            "the user's saved chip selection must win over ModelCache.pickBest (5x over-billing regression)",
        )
        assertFalse(fetchCalled, "saved selection must short-circuit before the expensive fetch")
    }

    @Test
    fun `blank override and blank saved selection fall through to auto-pick`() = runTest {
        val resolution = BrainFactory.resolveModel(
            modelOverride = "  ",
            savedModel = "",
            fetchBest = { "anthropic::auto-best" },
        )
        assertEquals(BrainFactory.ModelResolution.Use("anthropic::auto-best"), resolution)
    }

    @Test
    fun `factory fallback when nothing is configured and auto-pick finds nothing`() = runTest {
        val resolution = BrainFactory.resolveModel(
            modelOverride = null,
            savedModel = null,
            fetchBest = { null },
        )
        assertTrue(
            resolution is BrainFactory.ModelResolution.FactoryFallback,
            "no override, no saved model, and no fetchable best → factory fallback, was $resolution",
        )
    }
}

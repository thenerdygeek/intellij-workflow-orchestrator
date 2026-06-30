package com.workflow.orchestrator.core.ai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Contract tests for [AnthropicModelCatalogService] — the static, network-free
 * [ModelCatalogService] backed by [AnthropicModelCatalog].
 *
 * Phase 4a Task 3 — on the native Anthropic provider the Sourcegraph-backed catalog
 * serves nothing (blank URL), so window lookups must come from the static Anthropic
 * catalog keyed on the BARE model id (e.g. "claude-opus-4-8"), NOT fall back to the
 * 90K floor.
 */
class AnthropicModelCatalogServiceTest {

    private val svc = AnthropicModelCatalogService()

    @Test
    fun `is a ModelCatalogService`() {
        // Must be assignable to the type every AgentService catalog seam expects.
        val asBase: ModelCatalogService = svc
        assertNotNull(asBase)
    }

    @Test
    fun `getContextWindow uses bare id - opus 4_8 is 1M`() =
        assertEquals(1_000_000, svc.getContextWindow("claude-opus-4-8")?.maxInputTokens)

    @Test
    fun `getContextWindow uses bare id - sonnet 4_6 is 1M`() =
        assertEquals(1_000_000, svc.getContextWindow("claude-sonnet-4-6")?.maxInputTokens)

    @Test
    fun `getContextWindow uses bare id - haiku 4_5 is 200k`() =
        assertEquals(200_000, svc.getContextWindow("claude-haiku-4-5")?.maxInputTokens)

    @Test
    fun `getContextWindow carries maxOutput too`() =
        assertEquals(128_000, svc.getContextWindow("claude-opus-4-8")?.maxOutputTokens)

    @Test
    fun `unknown ref falls back to catalog default window, not null`() {
        val window = svc.getContextWindow("totally-unknown")
        assertNotNull(window)
        assertEquals(200_000, window?.maxInputTokens)
        assertEquals(64_000, window?.maxOutputTokens)
    }

    @Test
    fun `tier argument is ignored - same window for any tier`() {
        assertEquals(
            svc.getContextWindow("claude-opus-4-8", tier = "enterprise")?.maxInputTokens,
            svc.getContextWindow("claude-opus-4-8", tier = "free")?.maxInputTokens,
        )
    }

    @Test
    fun `supportsVision true for known catalogued models`() {
        assertTrue(svc.supportsVision("claude-opus-4-8"))
        assertTrue(svc.supportsVision("claude-haiku-4-5"))
    }

    @Test
    fun `supportsVision false for unknown model`() =
        assertFalse(svc.supportsVision("totally-unknown"))

    @Test
    fun `supportsTools true for known, false for unknown`() {
        assertTrue(svc.supportsTools("claude-opus-4-8"))
        assertFalse(svc.supportsTools("totally-unknown"))
    }

    @Test
    fun `getStatus stable for known, null for unknown`() {
        assertEquals("stable", svc.getStatus("claude-opus-4-8"))
        assertNull(svc.getStatus("totally-unknown"))
    }

    @Test
    fun `getLatestStreamApiVersion returns the default constant`() =
        assertEquals(ModelCatalogService.DEFAULT_STREAM_API_VERSION, svc.getLatestStreamApiVersion())
}

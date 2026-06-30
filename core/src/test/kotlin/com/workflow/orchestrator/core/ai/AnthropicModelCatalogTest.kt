package com.workflow.orchestrator.core.ai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AnthropicModelCatalogTest {

    @Test
    fun `sonnet 4_6 max output is 128k`() =
        assertEquals(128_000, AnthropicModelCatalog.maxOutput("claude-sonnet-4-6"))

    @Test
    fun `opus 4_8 context window is 1M`() =
        assertEquals(1_000_000, AnthropicModelCatalog.contextWindow("claude-opus-4-8"))

    @Test
    fun `defaults are bare ids`() {
        assertEquals("claude-opus-4-8", AnthropicModelCatalog.defaultModel())
        assertEquals("claude-sonnet-4-6", AnthropicModelCatalog.defaultSubagentModel())
        assertFalse(AnthropicModelCatalog.defaultSubagentModel().contains("::"))
    }

    @Test
    fun `unknown model falls back`() {
        assertEquals(200_000, AnthropicModelCatalog.contextWindow("nope"))
        assertEquals(64_000, AnthropicModelCatalog.maxOutput("nope"))
    }

    @Test
    fun `fallback chain is opus then sonnet`() =
        assertEquals(
            listOf("claude-opus-4-8", "claude-sonnet-4-6"),
            AnthropicModelCatalog.fallbackChain()
        )

    @Test
    fun `all four models are present in MODELS`() {
        val ids = AnthropicModelCatalog.MODELS.map { it.id }.toSet()
        assertTrue("claude-opus-4-8" in ids)
        assertTrue("claude-sonnet-4-6" in ids)
        assertTrue("claude-haiku-4-5" in ids)
        assertTrue("claude-fable-5" in ids)
    }

    @Test
    fun `entry returns non-null for known model`() =
        assertNotNull(AnthropicModelCatalog.entry("claude-haiku-4-5"))

    @Test
    fun `entry returns null for unknown model`() =
        assertNull(AnthropicModelCatalog.entry("nope"))

    @Test
    fun `haiku 4_5 context window is 200k and max output is 64k`() {
        val e = AnthropicModelCatalog.entry("claude-haiku-4-5")!!
        assertEquals(200_000, e.contextWindow)
        assertEquals(64_000, e.maxOutput)
    }

    @Test
    fun `fable 5 context window is 1M and max output is 128k`() {
        val e = AnthropicModelCatalog.entry("claude-fable-5")!!
        assertEquals(1_000_000, e.contextWindow)
        assertEquals(128_000, e.maxOutput)
    }

    @Test
    fun `all four models support vision`() {
        AnthropicModelCatalog.MODELS.forEach { assertTrue(it.supportsVision, "${it.id} must support vision") }
    }

    @Test
    fun `opus 4_8 max output is 128k`() =
        assertEquals(128_000, AnthropicModelCatalog.maxOutput("claude-opus-4-8"))

    @Test
    fun `haiku 4_5 context window via helper`() =
        assertEquals(200_000, AnthropicModelCatalog.contextWindow("claude-haiku-4-5"))

    @Test
    fun `maxOutput helper falls back to 64k for unknown model`() =
        assertEquals(64_000, AnthropicModelCatalog.maxOutput("unknown-model-xyz"))

    @Test
    fun `contextWindow helper falls back to 200k for unknown model`() =
        assertEquals(200_000, AnthropicModelCatalog.contextWindow("unknown-model-xyz"))
}

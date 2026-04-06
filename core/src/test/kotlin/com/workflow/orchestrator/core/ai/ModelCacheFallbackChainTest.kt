package com.workflow.orchestrator.core.ai

import com.workflow.orchestrator.core.ai.dto.ModelInfo
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class ModelCacheFallbackChainTest {

    private fun model(id: String, created: Long = 1000L) = ModelInfo(id = id, created = created)

    @Test
    fun `buildFallbackChain returns correct order with all tiers`() {
        val models = listOf(
            model("anthropic::v1::claude-opus-4-20250514-thinking"),
            model("anthropic::v1::claude-opus-4-20250514"),
            model("anthropic::v1::claude-sonnet-4-20250514-thinking"),
            model("anthropic::v1::claude-sonnet-4-20250514"),
            model("anthropic::v1::claude-haiku-4-20250514")
        )
        val chain = ModelCache.buildFallbackChain(models)
        assertEquals(4, chain.size)
        assertTrue(chain[0].contains("opus") && chain[0].contains("thinking"))
        assertTrue(chain[1].contains("opus") && !chain[1].contains("thinking"))
        assertTrue(chain[2].contains("sonnet") && chain[2].contains("thinking"))
        assertTrue(chain[3].contains("sonnet") && !chain[3].contains("thinking"))
    }

    @Test
    fun `buildFallbackChain skips missing tiers`() {
        val models = listOf(
            model("anthropic::v1::claude-opus-4-20250514"),
            model("anthropic::v1::claude-sonnet-4-20250514")
        )
        val chain = ModelCache.buildFallbackChain(models)
        assertEquals(2, chain.size)
        assertTrue(chain[0].contains("opus"))
        assertTrue(chain[1].contains("sonnet"))
    }

    @Test
    fun `buildFallbackChain returns empty for no anthropic models`() {
        val models = listOf(model("openai::v1::gpt-4"))
        val chain = ModelCache.buildFallbackChain(models)
        assertTrue(chain.isEmpty())
    }

    @Test
    fun `buildFallbackChain picks latest when multiple models per tier`() {
        val models = listOf(
            model("anthropic::v1::claude-opus-4-20250514", created = 1000),
            model("anthropic::v1::claude-opus-4-20260101", created = 2000),
            model("anthropic::v1::claude-sonnet-4-20250514", created = 1000)
        )
        val chain = ModelCache.buildFallbackChain(models)
        assertEquals(2, chain.size)
        assertEquals("anthropic::v1::claude-opus-4-20260101", chain[0])
    }

    @Test
    fun `buildFallbackChain excludes haiku`() {
        val models = listOf(model("anthropic::v1::claude-haiku-4-20250514"))
        val chain = ModelCache.buildFallbackChain(models)
        assertTrue(chain.isEmpty())
    }
}

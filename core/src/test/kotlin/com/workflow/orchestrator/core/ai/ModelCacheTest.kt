package com.workflow.orchestrator.core.ai

import com.workflow.orchestrator.core.ai.dto.ModelInfo
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ModelCacheTest {

    @BeforeEach
    fun reset() {
        ModelCache.reset()
    }

    @Test
    fun `pickBest prefers Opus thinking over plain Opus`() {
        val models = listOf(
            ModelInfo(id = "anthropic::2024-10-22::claude-sonnet-4-20250514", ownedBy = "anthropic", created = 1000),
            ModelInfo(id = "anthropic::2024-10-22::claude-opus-4-20250514", ownedBy = "anthropic", created = 2000),
            ModelInfo(id = "anthropic::2024-10-22::claude-opus-4-5-thinking-20250514", ownedBy = "anthropic", created = 3000)
        )
        val best = ModelCache.pickBest(models)
        assertNotNull(best)
        assertTrue(best!!.isOpusClass, "Should pick Opus class")
        assertTrue(best.isThinkingModel, "Should pick thinking model")
    }

    @Test
    fun `pickBest falls back to Opus when no thinking model`() {
        val models = listOf(
            ModelInfo(id = "anthropic::2024-10-22::claude-sonnet-4-20250514", ownedBy = "anthropic", created = 1000),
            ModelInfo(id = "anthropic::2024-10-22::claude-opus-4-20250514", ownedBy = "anthropic", created = 2000)
        )
        val best = ModelCache.pickBest(models)
        assertNotNull(best)
        assertTrue(best!!.isOpusClass)
    }

    @Test
    fun `pickBest falls back to Sonnet when no Opus`() {
        val models = listOf(
            ModelInfo(id = "anthropic::2024-10-22::claude-sonnet-4-20250514", ownedBy = "anthropic", created = 1000),
            ModelInfo(id = "anthropic::2024-10-22::claude-haiku-3-20250514", ownedBy = "anthropic", created = 2000)
        )
        val best = ModelCache.pickBest(models)
        assertNotNull(best)
        assertTrue(best!!.modelName.lowercase().contains("sonnet"))
    }

    @Test
    fun `pickBest picks latest among same tier`() {
        val models = listOf(
            ModelInfo(id = "anthropic::2024-10-22::claude-opus-4-5-thinking-20250101", ownedBy = "anthropic", created = 1000),
            ModelInfo(id = "anthropic::2024-10-22::claude-opus-4-5-thinking-20250514", ownedBy = "anthropic", created = 3000)
        )
        val best = ModelCache.pickBest(models)
        assertNotNull(best)
        assertEquals(3000, best!!.created)
    }

    @Test
    fun `pickBest returns null for empty list`() {
        assertNull(ModelCache.pickBest(emptyList()))
    }

    @Test
    fun `pickBest picks anything available as last resort`() {
        val models = listOf(
            ModelInfo(id = "openai::2024-01-01::gpt-4o", ownedBy = "openai", created = 1000)
        )
        val best = ModelCache.pickBest(models)
        assertNotNull(best)
        assertEquals("openai::2024-01-01::gpt-4o", best!!.id)
    }

    @Test
    fun `getCached returns empty when not populated`() {
        assertTrue(ModelCache.getCached().isEmpty())
    }

    @Test
    fun `populateFromExternal makes models available via getCached`() {
        val models = listOf(
            ModelInfo(id = "anthropic::2024-10-22::claude-sonnet-4-20250514", ownedBy = "anthropic", created = 1000)
        )
        ModelCache.populateFromExternal(models)
        assertEquals(1, ModelCache.getCached().size)
    }
}

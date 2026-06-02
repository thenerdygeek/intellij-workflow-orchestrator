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

    // ─────────────────────────────────────────────────────────────────────────
    // Cheap-model resolution must be robust to non-standard model id formats.
    // On an Anthropic-Vertex Sourcegraph instance the id prefix is NOT bare
    // "anthropic" (or the id may have no "::" at all → provider resolves to
    // "unknown"), so a `provider == "anthropic"` gate silently returns null and
    // the sanitizer falls through to the heavy configured model. The family is
    // identified by the model NAME (haiku/sonnet/opus are Anthropic-exclusive),
    // which is robust to id format.
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `pickHaiku finds Haiku when provider prefix is anthropic-vertex`() {
        val models = listOf(
            ModelInfo(id = "anthropic-vertex::2024-10-22::claude-haiku-4-5", ownedBy = "google", created = 2000),
            ModelInfo(id = "anthropic-vertex::2024-10-22::claude-sonnet-4-5", ownedBy = "google", created = 3000)
        )
        val haiku = ModelCache.pickHaiku(models)
        assertNotNull(haiku, "pickHaiku must resolve a Vertex-hosted Haiku")
        assertTrue(haiku!!.modelName.lowercase().contains("haiku"))
    }

    @Test
    fun `pickHaiku finds Haiku when id has no provider prefix`() {
        val models = listOf(
            ModelInfo(id = "claude-3-5-haiku-latest", ownedBy = "anthropic", created = 2000)
        )
        val haiku = ModelCache.pickHaiku(models)
        assertNotNull(haiku, "pickHaiku must resolve a Haiku id that has no '::' provider prefix")
    }

    @Test
    fun `pickSonnetNonThinking finds Sonnet on vertex-style ids`() {
        val models = listOf(
            ModelInfo(id = "anthropic-vertex::2024-10-22::claude-sonnet-4-5", ownedBy = "google", created = 3000)
        )
        val sonnet = ModelCache.pickSonnetNonThinking(models)
        assertNotNull(sonnet, "pickSonnetNonThinking must resolve a Vertex-hosted Sonnet")
        assertTrue(sonnet!!.modelName.lowercase().contains("sonnet"))
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

package com.workflow.orchestrator.agent.model

import com.workflow.orchestrator.core.ai.dto.ContextWindow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class EffectiveContextWindowTest {

    private fun win(maxInput: Int) =
        ContextWindow(maxInputTokens = maxInput, maxOutputTokens = 8000, maxUserInputTokens = null)

    private fun resolver(
        window: (String) -> ContextWindow? = { null },
        overrides: MaxTokenOverrides = MaxTokenOverrides(global = null, perModel = emptyMap()),
        fallback: Int = 90_000,
    ) = EffectiveContextWindow(windowLookup = window, overrides = { overrides }, fallback = fallback)

    private val m1Catalog: (String) -> ContextWindow? = { id -> if (id == "m1") win(132_000) else null }

    @Test
    fun `per-model override wins over global and catalog`() {
        val r = resolver(window = m1Catalog, overrides = MaxTokenOverrides(global = 50_000, perModel = mapOf("m1" to 70_000)))
        assertEquals(70_000, r.maxInputTokens("m1"))
    }

    @Test
    fun `global override applies when no per-model entry`() {
        val r = resolver(window = m1Catalog, overrides = MaxTokenOverrides(global = 50_000, perModel = emptyMap()))
        assertEquals(50_000, r.maxInputTokens("m1"))
    }

    @Test
    fun `catalog value used when no override`() {
        assertEquals(132_000, resolver(window = m1Catalog).maxInputTokens("m1"))
    }

    @Test
    fun `override may exceed catalog`() {
        val r = resolver(window = m1Catalog, overrides = MaxTokenOverrides(global = null, perModel = mapOf("m1" to 200_000)))
        assertEquals(200_000, r.maxInputTokens("m1"))
    }

    @Test
    fun `cache miss falls back when no override`() {
        assertEquals(90_000, resolver(window = { null }, fallback = 90_000).maxInputTokens("unknown"))
    }

    @Test
    fun `null or blank modelId still applies global override else fallback`() {
        assertEquals(50_000, resolver(overrides = MaxTokenOverrides(50_000, emptyMap())).maxInputTokens(null))
        assertEquals(90_000, resolver(fallback = 90_000).maxInputTokens(""))
    }

    @Test
    fun `catalogMaxInputTokens ignores overrides`() {
        val r = resolver(window = m1Catalog, overrides = MaxTokenOverrides(global = 10, perModel = mapOf("m1" to 10)))
        assertEquals(132_000, r.catalogMaxInputTokens("m1"))
    }
}

package com.workflow.orchestrator.agent.loop

import com.workflow.orchestrator.agent.model.EffectiveContextWindow
import com.workflow.orchestrator.agent.model.MaxTokenOverrides
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ContextManagerEffectiveWindowTest {

    @Test
    fun `effectiveMaxInputTokens uses resolver keyed on the running model`() {
        val resolver = EffectiveContextWindow(
            windowLookup = { null },
            overrides = { MaxTokenOverrides(global = null, perModel = mapOf("running-model" to 111_000)) },
            fallback = 90_000,
        )
        val cm = ContextManager(
            maxInputTokens = 150_000,
            currentModelRef = { "running-model" },
            effectiveContextWindow = resolver,
        )
        assertEquals(111_000, cm.effectiveMaxInputTokens())
    }

    @Test
    fun `without resolver falls back to existing behavior`() {
        val cm = ContextManager(maxInputTokens = 123_000)
        assertEquals(123_000, cm.effectiveMaxInputTokens())
    }
}

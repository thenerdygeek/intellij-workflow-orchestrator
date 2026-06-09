package com.workflow.orchestrator.agent.settings

import com.workflow.orchestrator.agent.model.MaxTokenOverrides
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AgentSettingsOverridesTest {

    @Test
    fun `default snapshot has no overrides`() {
        val s = AgentSettings.State()
        assertEquals(MaxTokenOverrides(global = null, perModel = emptyMap()), s.maxTokenOverridesSnapshot())
    }

    @Test
    fun `global zero maps to null, positive maps through`() {
        val s = AgentSettings.State().apply { maxTokenGlobalOverride = 0 }
        assertEquals(null, s.maxTokenOverridesSnapshot().global)
        s.maxTokenGlobalOverride = 50_000
        assertEquals(50_000, s.maxTokenOverridesSnapshot().global)
    }

    @Test
    fun `per-model json round-trips`() {
        val s = AgentSettings.State().apply { maxTokenPerModelOverrideJson = """{"m1":70000,"m2":120000}""" }
        assertEquals(mapOf("m1" to 70_000, "m2" to 120_000), s.maxTokenOverridesSnapshot().perModel)
    }

    @Test
    fun `malformed json yields empty map (never throws)`() {
        val s = AgentSettings.State().apply { maxTokenPerModelOverrideJson = "not json" }
        assertEquals(emptyMap<String, Int>(), s.maxTokenOverridesSnapshot().perModel)
    }
}

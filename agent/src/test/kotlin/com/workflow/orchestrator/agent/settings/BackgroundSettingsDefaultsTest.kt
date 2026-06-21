package com.workflow.orchestrator.agent.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BackgroundSettingsDefaultsTest {
    @Test fun `defaults are on, cap 5`() {
        val s = AgentSettings.State()
        assertTrue(s.allowToolsRunInBackground)
        assertEquals(5, s.maxBackgroundedToolsPerSession)
    }
}

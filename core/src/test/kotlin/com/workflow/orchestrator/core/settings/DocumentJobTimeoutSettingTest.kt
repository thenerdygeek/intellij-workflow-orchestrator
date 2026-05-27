package com.workflow.orchestrator.core.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DocumentJobTimeoutSettingTest {
    @Test
    fun `documentExtractionJobTimeoutMs defaults to 5 minutes`() {
        val state = PluginSettings.State()
        assertEquals(300_000L, state.documentExtractionJobTimeoutMs)
    }
}

package com.workflow.orchestrator.core.settings

import com.workflow.orchestrator.core.config.DefaultWorkflowConfig
import com.workflow.orchestrator.core.model.ServiceType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AnthropicSettingsTest {

    @Test
    fun `ANTHROPIC service type exists with display name`() {
        assertEquals("Anthropic", ServiceType.ANTHROPIC.displayName)
    }

    @Test
    fun `ConnectionSettings has anthropic api url default`() {
        assertEquals("https://api.anthropic.com", ConnectionSettings.State().anthropicApiUrl)
    }

    @Test
    fun `DefaultWorkflowConfig returns anthropicApiUrl for ANTHROPIC`() {
        val cfg = DefaultWorkflowConfig { ConnectionSettings.State(anthropicApiUrl = "https://api.anthropic.com") }
        assertEquals("https://api.anthropic.com", cfg.baseUrl(ServiceType.ANTHROPIC))
    }
}

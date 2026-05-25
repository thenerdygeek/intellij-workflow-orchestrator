package com.workflow.orchestrator.core.settings

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class WebEgressDenyListTest {

    @Test
    fun `getWebEgressDenyList returns empty list when JSON is blank`() {
        val settings = mockk<PluginSettings>()
        val state = PluginSettings.State()
        state.webEgressDenyListJson = ""
        every { settings.state } returns state
        assertEquals(emptyList<String>(), settings.getWebEgressDenyList())
    }

    @Test
    fun `getWebEgressDenyList round-trips entries`() {
        val settings = mockk<PluginSettings>()
        val state = PluginSettings.State()
        every { settings.state } returns state
        settings.setWebEgressDenyList(listOf("acme.corp", "re:Internal.*Service", "MyComp.class"))
        assertEquals(
            listOf("acme.corp", "re:Internal.*Service", "MyComp.class"),
            settings.getWebEgressDenyList()
        )
    }

    @Test
    fun `getWebEgressDenyList returns empty list on malformed JSON instead of throwing`() {
        val settings = mockk<PluginSettings>()
        val state = PluginSettings.State()
        state.webEgressDenyListJson = "{not valid json"
        every { settings.state } returns state
        // Fail-soft: corruption returns empty list (matches getWebAllowlist behavior).
        assertEquals(emptyList<String>(), settings.getWebEgressDenyList())
    }
}

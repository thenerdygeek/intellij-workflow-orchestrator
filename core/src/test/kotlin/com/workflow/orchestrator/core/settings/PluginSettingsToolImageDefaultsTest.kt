package com.workflow.orchestrator.core.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PluginSettingsToolImageDefaultsTest {

    @Test
    fun `enableToolImageAutoload defaults to true`() {
        assertTrue(PluginSettings.State().enableToolImageAutoload)
    }

    @Test
    fun `default MIME whitelist matches the user-paste whitelist (PNG, JPEG, WebP, GIF)`() {
        val ws = PluginSettings.State().toolImageAutoloadMimeWhitelist
        assertEquals(setOf("image/png", "image/jpeg", "image/webp", "image/gif"), ws)
    }
}

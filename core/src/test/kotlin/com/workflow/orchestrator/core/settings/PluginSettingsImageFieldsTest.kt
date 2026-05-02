package com.workflow.orchestrator.core.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Phase 5 — serialization round-trip + default-value tests for the five
 * image-attachment settings fields added to [PluginSettings.State]:
 *  - [PluginSettings.State.imageMaxBytes]
 *  - [PluginSettings.State.imagesPerTurnCap]
 *  - [PluginSettings.State.enableImageInput]
 *  - [PluginSettings.State.imageTokenEstimateDefault]
 *  - [PluginSettings.State.imageMimeWhitelist]
 *
 * Mirrors [PluginSettingsDocumentFieldsTest] — instantiate [PluginSettings.State]
 * directly to avoid needing a running IntelliJ application; the `by property()` /
 * `by stringList()` delegate paths do not call into platform services on default
 * construction.
 */
class PluginSettingsImageFieldsTest {

    // ── 1. Default values ───────────────────────────────────────────────────────

    @Test
    fun `new State has imageMaxBytes 5 MB (5_242_880 bytes)`() {
        val state = PluginSettings.State()
        assertEquals(5_242_880L, state.imageMaxBytes)
    }

    @Test
    fun `new State has imagesPerTurnCap 2`() {
        val state = PluginSettings.State()
        assertEquals(2, state.imagesPerTurnCap)
    }

    @Test
    fun `new State has enableImageInput true`() {
        val state = PluginSettings.State()
        assertTrue(state.enableImageInput)
    }

    @Test
    fun `new State has imageTokenEstimateDefault 1500`() {
        val state = PluginSettings.State()
        assertEquals(1500, state.imageTokenEstimateDefault)
    }

    @Test
    fun `default imageMimeWhitelist matches Cody whitelist`() {
        val state = PluginSettings.State()
        assertEquals(
            listOf("image/png", "image/jpeg", "image/webp", "image/heic", "image/heif"),
            state.imageMimeWhitelist.toList()
        )
    }

    // ── 2. Round-trip via mutation ──────────────────────────────────────────────

    @Test
    fun `imageMaxBytes round-trips to 10 MB`() {
        val state = PluginSettings.State()
        state.imageMaxBytes = 10_485_760L  // 10 MB
        assertEquals(10_485_760L, state.imageMaxBytes)
    }

    @Test
    fun `imagesPerTurnCap round-trips to 4`() {
        val state = PluginSettings.State()
        state.imagesPerTurnCap = 4
        assertEquals(4, state.imagesPerTurnCap)
    }

    @Test
    fun `enableImageInput round-trips to false`() {
        val state = PluginSettings.State()
        state.enableImageInput = false
        assertFalse(state.enableImageInput)
    }

    @Test
    fun `imageTokenEstimateDefault round-trips to 3000`() {
        val state = PluginSettings.State()
        state.imageTokenEstimateDefault = 3000
        assertEquals(3000, state.imageTokenEstimateDefault)
    }

    @Test
    fun `imageMimeWhitelist supports add and remove`() {
        val state = PluginSettings.State()
        state.imageMimeWhitelist.add("image/gif")
        assertTrue(state.imageMimeWhitelist.contains("image/gif"))

        state.imageMimeWhitelist.remove("image/heic")
        assertFalse(state.imageMimeWhitelist.contains("image/heic"))
    }

    @Test
    fun `imageMimeWhitelist can be replaced wholesale`() {
        val state = PluginSettings.State()
        state.imageMimeWhitelist.clear()
        state.imageMimeWhitelist.addAll(listOf("image/png", "image/jpeg"))
        assertEquals(listOf("image/png", "image/jpeg"), state.imageMimeWhitelist.toList())
    }
}

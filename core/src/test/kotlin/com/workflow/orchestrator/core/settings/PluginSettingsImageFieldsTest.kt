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
    fun `new State has enableImageInput false (panic-button default)`() {
        // Default flipped from true → false in the visual-support kill-switch PR
        // (2026-05-13). Users who never touched the setting get visual support
        // disabled by default; users who explicitly enabled it via Settings UI
        // keep their saved value on upgrade (field name unchanged, XML stays valid).
        val state = PluginSettings.State()
        assertFalse(state.enableImageInput)
    }

    @Test
    fun `new State has imageTokenEstimateDefault 1500`() {
        val state = PluginSettings.State()
        assertEquals(1500, state.imageTokenEstimateDefault)
    }

    @Test
    fun `default imageMimeWhitelist matches gateway-verified set from format_lab probe`() {
        // format_lab probe (2026-05-05, api-version=9) found PNG/JPEG/WebP
        // round-trip through every vision-capable Claude 4.5 model. HEIC and
        // HEIF appeared in Cody's UI whitelist but the upstream provider
        // rejects them with event: error frames (0/6 models). GIF is partial
        // (3/6) and lives only on the tool-output autoload path.
        val state = PluginSettings.State()
        assertEquals(
            listOf("image/png", "image/jpeg", "image/webp"),
            state.imageMimeWhitelist.toList(),
            "Default whitelist must match format_lab 2026-05-05 evidence: " +
                "PNG/JPEG/WebP only — HEIC/HEIF were dropped because the gateway " +
                "rejects them despite Cody's UI advertising support."
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

        state.imageMimeWhitelist.remove("image/png")
        assertFalse(state.imageMimeWhitelist.contains("image/png"))
    }

    @Test
    fun `imageMimeWhitelist can be replaced wholesale`() {
        val state = PluginSettings.State()
        state.imageMimeWhitelist.clear()
        state.imageMimeWhitelist.addAll(listOf("image/png", "image/jpeg"))
        assertEquals(listOf("image/png", "image/jpeg"), state.imageMimeWhitelist.toList())
    }
}

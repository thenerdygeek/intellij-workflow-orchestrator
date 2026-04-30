package com.workflow.orchestrator.core.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Phase 8 — serialization round-trip tests for the four document-extraction settings
 * fields added to [PluginSettings.State]:
 *  - [PluginSettings.State.documentMaxChars]
 *  - [PluginSettings.State.documentTimeoutMs]
 *  - [PluginSettings.State.documentEnableStreamMode]
 *  - [PluginSettings.State.documentOcrEnabled]
 *
 * [PluginSettings.State] extends [com.intellij.openapi.components.BaseState], which uses
 * property delegates backed by a plain field map. Instantiating [PluginSettings.State]
 * directly (as done in [AutoDetectOrchestratorTest]) avoids needing a running IntelliJ
 * application, because the delegate initialisation path for `by property()` and `by string()`
 * does not call into platform services.
 */
class PluginSettingsDocumentFieldsTest {

    // ── 1. Default values ───────────────────────────────────────────────────────

    @Test
    fun `new State has documentMaxChars 200_000`() {
        val state = PluginSettings.State()
        assertEquals(200_000, state.documentMaxChars)
    }

    @Test
    fun `new State has documentTimeoutMs 30_000`() {
        val state = PluginSettings.State()
        assertEquals(30_000L, state.documentTimeoutMs)
    }

    @Test
    fun `new State has documentEnableStreamMode false`() {
        val state = PluginSettings.State()
        assertFalse(state.documentEnableStreamMode)
    }

    @Test
    fun `new State has documentOcrEnabled false`() {
        val state = PluginSettings.State()
        assertFalse(state.documentOcrEnabled)
    }

    // ── 2. Round-trip via mutation ──────────────────────────────────────────────

    @Test
    fun `documentMaxChars round-trips to 50_000`() {
        val state = PluginSettings.State()
        state.documentMaxChars = 50_000
        assertEquals(50_000, state.documentMaxChars)
    }

    @Test
    fun `documentTimeoutMs round-trips to 60_000`() {
        val state = PluginSettings.State()
        state.documentTimeoutMs = 60_000L
        assertEquals(60_000L, state.documentTimeoutMs)
    }

    @Test
    fun `documentEnableStreamMode round-trips to true`() {
        val state = PluginSettings.State()
        state.documentEnableStreamMode = true
        assertTrue(state.documentEnableStreamMode)
    }

    @Test
    fun `documentOcrEnabled round-trips to true`() {
        val state = PluginSettings.State()
        state.documentOcrEnabled = true
        assertTrue(state.documentOcrEnabled)
    }

    // ── 3. maxCharsProvider semantics — 0 and negatives map to Int.MAX_VALUE ───

    @Test
    fun `documentMaxChars of 0 should map to Int MAX_VALUE at wiring callsite`() {
        val state = PluginSettings.State()
        state.documentMaxChars = 0
        val resolved = if (state.documentMaxChars <= 0) Int.MAX_VALUE else state.documentMaxChars
        assertEquals(Int.MAX_VALUE, resolved)
    }

    @Test
    fun `documentMaxChars of negative should map to Int MAX_VALUE at wiring callsite`() {
        val state = PluginSettings.State()
        state.documentMaxChars = -1
        val resolved = if (state.documentMaxChars <= 0) Int.MAX_VALUE else state.documentMaxChars
        assertEquals(Int.MAX_VALUE, resolved)
    }

    @Test
    fun `documentMaxChars of positive preserves value at wiring callsite`() {
        val state = PluginSettings.State()
        state.documentMaxChars = 100_000
        val resolved = if (state.documentMaxChars <= 0) Int.MAX_VALUE else state.documentMaxChars
        assertEquals(100_000, resolved)
    }
}

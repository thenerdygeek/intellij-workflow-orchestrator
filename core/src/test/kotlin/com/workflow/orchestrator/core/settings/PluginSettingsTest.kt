package com.workflow.orchestrator.core.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for [PluginSettings.State] fields that do not fit an existing focused test class.
 *
 * [PluginSettings.State] extends [com.intellij.openapi.components.BaseState], whose
 * property delegates (`by property()`, `by string()`, `by list<T>()`) do not call into
 * platform services during construction, so the State can be instantiated in plain JUnit
 * without a running IntelliJ application.
 */
class PluginSettingsTest {

    @Test
    fun `quickClipboardChips default is the canonical 8-item list`() {
        val state = PluginSettings.State()
        assertEquals(
            listOf(
                "docker.tag", "docker.tagsJson", "pr.url", "build.url",
                "automation.url", "ticket.id", "ai.changeSummary", "ai.ticketSummary"
            ),
            state.quickClipboardChips.toList()
        )
    }

    @Test
    fun `aiSummariesEnabled defaults to true`() {
        val state = PluginSettings.State()
        assertTrue(state.aiSummariesEnabled)
    }

    @Test
    fun `aiSummariesEnabled can be set to false`() {
        val state = PluginSettings.State()
        state.aiSummariesEnabled = false
        assertFalse(state.aiSummariesEnabled)
    }

    @Test
    fun `handoverOverrideLog defaults to empty`() {
        val state = PluginSettings.State()
        assertTrue(state.handoverOverrideLog.isEmpty())
    }

    @Test
    fun `handoverOverrideLog accepts and retains entries`() {
        val state = PluginSettings.State()
        state.handoverOverrideLog.add("2026-04-01T10:00:00Z")
        state.handoverOverrideLog.add("2026-04-02T10:00:00Z")
        assertEquals(2, state.handoverOverrideLog.size)
        assertEquals("2026-04-01T10:00:00Z", state.handoverOverrideLog.first())
    }
}

package com.workflow.orchestrator.core.settings

import org.junit.jupiter.api.Assertions.assertEquals
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
}

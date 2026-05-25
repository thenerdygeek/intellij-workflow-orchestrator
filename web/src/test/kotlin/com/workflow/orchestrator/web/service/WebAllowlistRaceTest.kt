package com.workflow.orchestrator.web.service

import com.workflow.orchestrator.core.model.web.DomainAllowlistEntry
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.settings.getWebAllowlist
import com.workflow.orchestrator.core.settings.setWebAllowlist
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Regression tests for I7 — allowlist read/write race + silent JSON parse failure.
 *
 * The first test exercises [PluginSettings.getWebAllowlist] on a corrupted JSON payload.
 * Behaviour is fail-soft (return empty), but post-I7 the parse failure is logged at WARN
 * so silent corruption is surfaced; the test asserts the call still returns empty AND
 * does not throw (so the agent loop keeps running).
 *
 * The second test exercises [PluginSettings.setWebAllowlist] / `getWebAllowlist` round-trip
 * to confirm the mutex-guarded persistence path doesn't regress the basic functionality.
 */
class WebAllowlistRaceTest {

    @Test
    fun `getWebAllowlist returns empty and does not throw on malformed JSON`() {
        val state = PluginSettings.State().apply {
            webAllowlistJson = "{this is not valid json at all"
        }
        val settings = mockk<PluginSettings>(relaxed = true)
        every { settings.state } returns state

        // I7: must not throw, must return empty (fail-soft), corruption goes to the WARN log.
        val list = settings.getWebAllowlist()
        assertTrue(list.isEmpty(), "Malformed JSON must return empty, got: $list")
    }

    @Test
    fun `setWebAllowlist then getWebAllowlist round-trips a single entry`() {
        val state = PluginSettings.State().apply {
            webAllowlistJson = "[]"
        }
        val settings = mockk<PluginSettings>(relaxed = true)
        every { settings.state } returns state

        val entry = DomainAllowlistEntry(
            domain = "docs.example.com",
            httpOk = false,
            addedAt = Instant.parse("2026-05-24T00:00:00Z"),
        )
        settings.setWebAllowlist(listOf(entry))

        val read = settings.getWebAllowlist()
        assertEquals(1, read.size)
        assertEquals("docs.example.com", read[0].domain)
        assertEquals(false, read[0].httpOk)
    }
}

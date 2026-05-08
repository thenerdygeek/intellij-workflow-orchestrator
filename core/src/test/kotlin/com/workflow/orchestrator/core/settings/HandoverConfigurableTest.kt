package com.workflow.orchestrator.core.settings

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Unit tests for [HandoverConfigurable].
 *
 * [PluginSettings.State] extends [com.intellij.openapi.components.BaseState]; its property
 * delegates do not call platform services during construction, so State can be instantiated
 * in plain JUnit. [HandoverConfigurable] is constructed with a mocked [Project] that returns
 * a mocked [PluginSettings] backed by a real [PluginSettings.State].
 */
class HandoverConfigurableTest {

    private val pluginSettings = mockk<PluginSettings>(relaxed = true)
    private val state = PluginSettings.State()
    private val project = mockk<com.intellij.openapi.project.Project>(relaxed = true)

    init {
        every { project.getService(PluginSettings::class.java) } returns pluginSettings
        every { pluginSettings.state } returns state
        // basePath is used by HandoverConfigurable to compute projectDir
        every { project.basePath } returns "/tmp/test-project"
    }

    private fun configurable() = HandoverConfigurable(project)

    // -----------------------------------------------------------------------
    // Basic contract
    // -----------------------------------------------------------------------

    @Test
    fun `display name is Handover`() {
        assertEquals("Handover", configurable().displayName)
    }

    @Test
    fun `id is workflow orchestrator handover`() {
        assertEquals("workflow.orchestrator.handover", configurable().id)
    }

    // -----------------------------------------------------------------------
    // createComponent
    // -----------------------------------------------------------------------

    @Test
    fun `createComponent returns a non-null component`() {
        val comp = configurable().createComponent()
        assertNotNull(comp)
    }

    // -----------------------------------------------------------------------
    // Quick clipboard chips
    // -----------------------------------------------------------------------

    @Test
    fun `PLACEHOLDER_CATALOG contains 14 keys`() {
        assertEquals(14, HandoverConfigurable.PLACEHOLDER_CATALOG.size)
    }

    @Test
    fun `PLACEHOLDER_CATALOG includes all expected keys`() {
        val catalog = HandoverConfigurable.PLACEHOLDER_CATALOG
        assertTrue("ticket.id" in catalog)
        assertTrue("pr.url" in catalog)
        assertTrue("docker.tag" in catalog)
        assertTrue("ai.changeSummary" in catalog)
        assertTrue("ai.ticketSummary" in catalog)
        assertTrue("automation.suiteTable" in catalog)
    }

    @Test
    fun `removing chip from state removes it from quickClipboardChips`() {
        assertTrue(state.quickClipboardChips.contains("docker.tag"))
        state.quickClipboardChips.remove("docker.tag")
        assertFalse(state.quickClipboardChips.contains("docker.tag"))
    }

    @Test
    fun `adding chip to state reflects in quickClipboardChips`() {
        state.quickClipboardChips.remove("ticket.status")
        assertFalse(state.quickClipboardChips.contains("ticket.status"))
        state.quickClipboardChips.add("ticket.status")
        assertTrue(state.quickClipboardChips.contains("ticket.status"))
    }

    // -----------------------------------------------------------------------
    // AI summaries toggle
    // -----------------------------------------------------------------------

    @Test
    fun `aiSummariesEnabled defaults to true`() {
        assertTrue(state.aiSummariesEnabled)
    }

    @Test
    fun `aiSummariesEnabled can be toggled via state`() {
        state.aiSummariesEnabled = false
        assertFalse(state.aiSummariesEnabled)
        state.aiSummariesEnabled = true
        assertTrue(state.aiSummariesEnabled)
    }

    // -----------------------------------------------------------------------
    // Override audit — count30d via HandoverConfigurable.count30d()
    // -----------------------------------------------------------------------

    @Test
    fun `count30d returns zero when log is empty`() {
        state.handoverOverrideLog.clear()
        val c = configurable()
        assertEquals(0, c.count30d())
    }

    @Test
    fun `count30d counts recent entries`() {
        state.handoverOverrideLog.clear()
        state.handoverOverrideLog.add(DateTimeFormatter.ISO_INSTANT.format(Instant.now().minusSeconds(3600)))
        state.handoverOverrideLog.add(DateTimeFormatter.ISO_INSTANT.format(Instant.now().minusSeconds(7200)))
        val c = configurable()
        assertEquals(2, c.count30d())
    }

    @Test
    fun `count30d excludes entries older than 30 days`() {
        state.handoverOverrideLog.clear()
        val old = Instant.now().minus(31, ChronoUnit.DAYS)
        state.handoverOverrideLog.add(DateTimeFormatter.ISO_INSTANT.format(old))
        state.handoverOverrideLog.add(DateTimeFormatter.ISO_INSTANT.format(Instant.now().minusSeconds(60)))
        val c = configurable()
        assertEquals(1, c.count30d())
    }

    @Test
    fun `count30d does not mutate the log — stale entry is excluded but not removed`() {
        state.handoverOverrideLog.clear()
        val old = Instant.now().minus(31, ChronoUnit.DAYS)
        state.handoverOverrideLog.add(DateTimeFormatter.ISO_INSTANT.format(old))
        val c = configurable()
        assertEquals(0, c.count30d())
        // count30d is read-only; the entry remains in the list (pruning is the tracker's job)
        assertEquals(1, state.handoverOverrideLog.size)
    }

    @Test
    fun `clearing the log sets count to zero`() {
        state.handoverOverrideLog.add(DateTimeFormatter.ISO_INSTANT.format(Instant.now()))
        state.handoverOverrideLog.clear()
        val c = configurable()
        assertEquals(0, c.count30d())
    }

    // -----------------------------------------------------------------------
    // isModified / apply / reset lifecycle
    // -----------------------------------------------------------------------

    @Test
    fun `isModified returns false before createComponent is called`() {
        assertFalse(configurable().isModified)
    }

    @Test
    fun `apply and reset do not throw before createComponent is called`() {
        val c = configurable()
        assertDoesNotThrow { c.apply() }
        assertDoesNotThrow { c.reset() }
    }

    @Test
    fun `disposeUIResources clears panel reference`() {
        val c = configurable()
        c.createComponent()
        assertDoesNotThrow { c.disposeUIResources() }
        // After dispose, isModified must not throw
        assertDoesNotThrow { c.isModified }
    }
}

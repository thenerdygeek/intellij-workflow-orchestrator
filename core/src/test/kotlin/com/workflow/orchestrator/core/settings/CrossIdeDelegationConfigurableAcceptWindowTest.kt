package com.workflow.orchestrator.core.settings

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Focused tests for the [CrossIdeDelegationConfigurable] accept-window field:
 * [PluginSettings.State.delegationAcceptWindowSeconds].
 *
 * Mirrors the pattern of HandoverConfigurableTest: construct with a mocked
 * [Project] → mocked [PluginSettings] → real [PluginSettings.State]. Verifies
 * the isModified / apply / reset lifecycle for the new spinner field, following
 * the same DialogPanel pattern the existing fields use.
 *
 * NOTE: [CrossIdeDelegationConfigurable] uses a [com.intellij.ui.JBIntSpinner] with
 * a live change-listener (same as [delegationIdleTimeoutMinutes]) rather than a
 * DSL bind — the configurable must hold the accept-window spinner reference and
 * reflect it through apply/reset.
 */
class CrossIdeDelegationConfigurableAcceptWindowTest {

    private val pluginSettings = mockk<PluginSettings>(relaxed = true)
    private val state = PluginSettings.State()
    private val project = mockk<com.intellij.openapi.project.Project>(relaxed = true)

    init {
        every { project.getService(PluginSettings::class.java) } returns pluginSettings
        every { pluginSettings.state } returns state
    }

    private fun configurable() = CrossIdeDelegationConfigurable(project)

    // ── Pre-createComponent safety ──────────────────────────────────────────

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

    // ── apply persists the accept-window value ──────────────────────────────

    @Test
    fun `apply writes delegationAcceptWindowSeconds to settings`() {
        state.delegationAcceptWindowSeconds = 55
        val c = configurable()
        c.createComponent()
        // Simulate the human changing the spinner to 120
        c.setAcceptWindowSecondsForTest(120)
        c.apply()
        assertEquals(120, state.delegationAcceptWindowSeconds)
    }

    @Test
    fun `apply writes default value (55) when spinner is unchanged`() {
        state.delegationAcceptWindowSeconds = 55
        val c = configurable()
        c.createComponent()
        c.apply()
        assertEquals(55, state.delegationAcceptWindowSeconds)
    }

    // ── isModified correctly reflects changes ───────────────────────────────

    @Test
    fun `isModified returns true when accept window spinner differs from settings`() {
        state.delegationAcceptWindowSeconds = 55
        val c = configurable()
        c.createComponent()
        c.setAcceptWindowSecondsForTest(90)
        assertTrue(c.isModified, "isModified must be true when spinner differs from persisted value")
    }

    @Test
    fun `isModified returns false when accept window spinner matches settings`() {
        state.delegationAcceptWindowSeconds = 55
        val c = configurable()
        c.createComponent()
        // No change; spinner initialises from settings
        assertFalse(c.isModified, "isModified must be false when spinner matches persisted value")
    }

    // ── reset restores spinner from settings ────────────────────────────────

    @Test
    fun `reset restores accept window spinner to persisted settings value`() {
        state.delegationAcceptWindowSeconds = 90
        val c = configurable()
        c.createComponent()
        // Change the spinner locally
        c.setAcceptWindowSecondsForTest(200)
        // Reset must restore it from settings (90)
        c.reset()
        // After reset, isModified must be false (spinner matches settings again)
        assertFalse(c.isModified, "after reset, isModified must be false")
    }

    // ── round-trip: apply then reset ────────────────────────────────────────

    @Test
    fun `round-trip apply then reset leaves isModified false`() {
        state.delegationAcceptWindowSeconds = 55
        val c = configurable()
        c.createComponent()
        c.setAcceptWindowSecondsForTest(300)
        c.apply()
        assertEquals(300, state.delegationAcceptWindowSeconds)
        c.reset()
        assertFalse(c.isModified)
    }

    // ── disposeUIResources ──────────────────────────────────────────────────

    @Test
    fun `disposeUIResources does not throw and isModified is safe after dispose`() {
        val c = configurable()
        c.createComponent()
        assertDoesNotThrow { c.disposeUIResources() }
        assertDoesNotThrow { c.isModified }
    }
}

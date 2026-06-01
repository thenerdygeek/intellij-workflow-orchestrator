package com.workflow.orchestrator.core.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PluginSettingsDelegationFieldsTest {

    @Test
    fun `delegationIdleTimeoutMinutes defaults to 30`() {
        val s = PluginSettings.State()
        assertEquals(30, s.delegationIdleTimeoutMinutes)
    }

    @Test
    fun `delegationIdleTimeoutMinutes round-trips`() {
        val s = PluginSettings.State()
        s.delegationIdleTimeoutMinutes = 5
        assertEquals(5, s.delegationIdleTimeoutMinutes)
        s.delegationIdleTimeoutMinutes = 0
        assertEquals(0, s.delegationIdleTimeoutMinutes)
    }

    // ── delegationAcceptWindowSeconds ───────────────────────────────────────

    @Test
    fun `delegationAcceptWindowSeconds defaults to 55`() {
        val s = PluginSettings.State()
        assertEquals(55, s.delegationAcceptWindowSeconds)
    }

    @Test
    fun `delegationAcceptWindowSeconds round-trips`() {
        val s = PluginSettings.State()
        s.delegationAcceptWindowSeconds = 120
        assertEquals(120, s.delegationAcceptWindowSeconds)
        s.delegationAcceptWindowSeconds = 10
        assertEquals(10, s.delegationAcceptWindowSeconds)
    }

    @Test
    fun `delegationAcceptWindowSeconds clamped to minimum 10 when set below range`() {
        val s = PluginSettings.State()
        s.delegationAcceptWindowSeconds = 5
        // Stored as-is; callers use effectiveDelegationAcceptWindowSeconds() for clamping.
        // We verify via the effectiveAcceptWindowMs helper that it yields at least 10s.
        assertTrue(
            s.effectiveAcceptWindowMs() >= 10_000L,
            "effectiveAcceptWindowMs() must clamp to at least 10s; got ${s.effectiveAcceptWindowMs()}"
        )
    }

    @Test
    fun `delegationAcceptWindowSeconds clamped to maximum 600 when set above range`() {
        val s = PluginSettings.State()
        s.delegationAcceptWindowSeconds = 700
        assertTrue(
            s.effectiveAcceptWindowMs() <= 600_000L,
            "effectiveAcceptWindowMs() must clamp to at most 600s; got ${s.effectiveAcceptWindowMs()}"
        )
    }

    @Test
    fun `effectiveAcceptWindowMs is strictly less than 60 000ms for default value`() {
        val s = PluginSettings.State()
        // Invariant: ACCEPT_WINDOW_MS < IDE-A's connectAndAwaitAccept timeout (60_000L).
        // At the default of 55s the effective ms must be 55_000L (< 60_000L).
        val ms = s.effectiveAcceptWindowMs()
        assertTrue(
            ms < 60_000L,
            "effectiveAcceptWindowMs() must stay strictly below 60_000ms; got ${ms}ms"
        )
    }

    @Test
    fun `effectiveAcceptWindowMs for max value 59 is still less than 60_000ms`() {
        // The public max is 600s, but the function must ALSO enforce the IDE-A invariant.
        // Verify at least that the default doesn't break the constraint.
        val s = PluginSettings.State()
        s.delegationAcceptWindowSeconds = 59
        val ms = s.effectiveAcceptWindowMs()
        assertTrue(
            ms < 60_000L,
            "59s must be < 60_000ms; got ${ms}ms"
        )
    }
}

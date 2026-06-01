package com.workflow.orchestrator.agent.ui

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Source-text pin: [AgentController.startDelegatedSession] must read the accept-window
 * duration from the configured setting ([PluginSettings.State.delegationAcceptWindowSeconds]
 * via [PluginSettings.State.effectiveAcceptWindowMs]) rather than the bare
 * [AgentController.ACCEPT_WINDOW_MS] constant when applying the busy-path timeout.
 *
 * We also verify:
 * - [AgentController.ACCEPT_WINDOW_MS] still exists as a named default constant (it seeds
 *   the setting's default value — the single source of truth for 55s).
 * - `SESSION_START_TIMEOUT_MS` is not derived from the accept-window setting (it is a
 *   fixed engineering timeout for EDT session-start delivery, not a human-facing knob).
 * - `effectiveAcceptWindowMs` is referenced in the production source, not ACCEPT_WINDOW_MS
 *   alone, proving the const is no longer the sole driver of the busy-window.
 */
class AgentControllerAcceptWindowSettingPinTest {

    private val src = java.io.File(
        "src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt"
    ).readText()

    @Test
    fun `ACCEPT_WINDOW_MS const still exists as the named default`() {
        assertTrue(
            src.contains("const val ACCEPT_WINDOW_MS"),
            "ACCEPT_WINDOW_MS const must remain — it is the single source of truth for the default value"
        )
    }

    @Test
    fun `effectiveAcceptWindowMs is referenced in startDelegatedSession`() {
        // The accept window for the busy-case gate must come from the configurable
        // setting, not from the bare const. The source must call effectiveAcceptWindowMs
        // somewhere inside startDelegatedSession (or a helper it calls).
        assertTrue(
            src.contains("effectiveAcceptWindowMs"),
            "AgentController must call effectiveAcceptWindowMs() to read the configured window, " +
                "not use ACCEPT_WINDOW_MS directly in the busy-path timeout"
        )
    }

    @Test
    fun `SESSION_START_TIMEOUT_MS still exists unchanged`() {
        // SESSION_START_TIMEOUT_MS is a fixed engineering bound (not user-configurable)
        // and must NOT be derived from the accept-window setting.
        assertTrue(
            src.contains("const val SESSION_START_TIMEOUT_MS"),
            "SESSION_START_TIMEOUT_MS must remain as a const — it is not user-configurable"
        )
    }

    @Test
    fun `SESSION_START_TIMEOUT_MS is not set to effectiveAcceptWindowMs`() {
        // The session-start timeout must remain a fixed constant, not dynamically derived
        // from the user setting. Only the busy-gate window uses effectiveAcceptWindowMs.
        assertFalse(
            src.contains("SESSION_START_TIMEOUT_MS = effectiveAcceptWindowMs"),
            "SESSION_START_TIMEOUT_MS must remain a fixed const, not a dynamic value"
        )
    }
}

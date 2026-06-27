package com.workflow.orchestrator.agent.ui

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * BUG-3: opening a HISTORY conversation (via [AgentController.showSession]) while the previous
 * agent loop is still streaming / tearing down let the prior session's leftover `<thinking>`
 * deltas bleed into the opened history session's reasoning collapsible — it kept appending and
 * never finalized.
 *
 * Root cause (two parts):
 *   1. `showSession` rehydrates a history conversation but never cleared the pre-bridge
 *      stream/thinking buffers, so in-flight state from the loop being left carried over.
 *   2. The thinking bridge (`appendToThinking` / `endThinking`) was the ONLY live webview push
 *      that was NOT session-gated — every sibling push (delegation cards, async-event cards)
 *      drops when `viewedSessionId != sessionId`, but thinking pushed unconditionally.
 *
 * Fix:
 *   1. `showSession` calls `clearStream()` (the same teardown `cancelTask`/`newChat` use) so
 *      leftover stream/thinking buffers are dropped when a history session loads.
 *   2. The thinking append + finalize are gated by [streamingSessionIsOnScreen] — the streaming
 *      session is the live `currentSessionId`; it is "on screen" when `viewedSessionId` is null
 *      (normal active chat, where the live session IS the panel) OR equals `currentSessionId`.
 *      A DIFFERENT viewed history session drops the leftover deltas.
 *
 * ⚠ Over-gating guard: the helper MUST treat a null `viewedSessionId` as on-screen, otherwise
 * live thinking for the normal active session would be suppressed (the #1 risk of this fix).
 *
 * AgentController is not unit-instantiable (`AgentService.getInstance(project)` + a JCEF
 * dashboard), so per repo precedent (see [AgentControllerShowSessionOffEdtTest],
 * [AgentControllerResumeParkSourceTest]) this pins the source text of the affected sites.
 */
class AgentControllerThinkingSessionGateTest {

    private val src = java.io.File(
        "src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt"
    ).readText()

    // ── Fix #1: showSession clears the stream buffers ───────────────────────────────────────

    @Test
    fun `showSession clears the pre-bridge stream and thinking buffers`() {
        val slice = src
            .substringAfter("fun showSession(sessionId: String) {")
            .substringBefore("fun resumeViewedSession(")
        assertTrue(
            slice.contains("clearStream()"),
            "showSession must call clearStream() so leftover <thinking>/stream buffers from the " +
                "loop being left can't bleed into the history session being displayed",
        )
        // The clear must happen on EDT phase 1 (before the expensive IO load is dispatched).
        val clearIdx = slice.indexOf("clearStream()")
        val launchIdx = slice.indexOf("controllerScope.launch(Dispatchers.IO")
        assertTrue(launchIdx >= 0, "showSession still hops to Dispatchers.IO")
        assertTrue(
            clearIdx in 0 until launchIdx,
            "clearStream() must run on EDT phase 1, before the IO load hop",
        )
    }

    // ── Fix #2: the thinking bridge is session-gated ────────────────────────────────────────

    @Test
    fun `the on-screen helper exists and does NOT over-gate the normal active session`() {
        assertTrue(
            src.contains("fun streamingSessionIsOnScreen()"),
            "a streamingSessionIsOnScreen() helper must gate the thinking bridge",
        )
        val body = src
            .substringAfter("fun streamingSessionIsOnScreen()")
            .substringBefore("\n    private fun ")
        // Over-gating guard: a null viewedSessionId (normal active chat) MUST count as on-screen.
        assertTrue(
            body.contains("viewedSessionId == null"),
            "streamingSessionIsOnScreen() must treat a null viewedSessionId as on-screen — " +
                "otherwise live thinking for the normal active session is wrongly suppressed",
        )
        // It compares the streaming session (currentSessionId) against the viewed session.
        assertTrue(
            body.contains("viewedSessionId == currentSessionId"),
            "streamingSessionIsOnScreen() must compare the streaming session (currentSessionId) " +
                "to the viewed session",
        )
    }

    @Test
    fun `appendToThinking is gated by the on-screen check`() {
        // The thinkingStreamBatcher.onFlush is the actual bridge push for live thinking deltas.
        val slice = src
            .substringAfter("private val thinkingStreamBatcher = StreamBatcher(")
            .substringBefore("private val thinkingSplitter")
        val pushIdx = slice.indexOf("dashboard.appendToThinking")
        val gateIdx = slice.indexOf("streamingSessionIsOnScreen()")
        assertTrue(pushIdx >= 0, "the thinking batcher must still push via dashboard.appendToThinking")
        assertTrue(
            gateIdx in 0 until pushIdx,
            "appendToThinking must be guarded by streamingSessionIsOnScreen() so off-screen " +
                "thinking deltas are dropped (BUG-3 bleed)",
        )
    }

    @Test
    fun `endThinking is gated by the on-screen check`() {
        val slice = src
            .substringAfter("private fun dispatchSplitParts(")
            .substringBefore("private fun flushStream(")
        val endIdx = slice.indexOf("dashboard.endThinking")
        val gateIdx = slice.lastIndexOf("streamingSessionIsOnScreen()", endIdx.coerceAtLeast(0))
        assertTrue(endIdx >= 0, "dispatchSplitParts must still close the block via dashboard.endThinking")
        assertTrue(
            gateIdx in 0 until endIdx,
            "endThinking must be guarded by streamingSessionIsOnScreen() so the block is not " +
                "finalized into an off-screen history session",
        )
    }
}

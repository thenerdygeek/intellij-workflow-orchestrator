package com.workflow.orchestrator.agent.ui

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Source-contract pin for audit P0-7: a History click ran `MessageStateHandler.loadUiMessages`
 * (disk read + JSON decode of a multi-MB file) + `postStateToWebview` (full re-encode +
 * JS-escape) synchronously in the EDT bridge handler — a visible freeze on every session open.
 *
 * The fix mirrors [AgentController.showHistory]: load + serialize on Dispatchers.IO via
 * `controllerScope`, push on EDT via `invokeLater`. The `killBackgroundsOnTransition` modal
 * decision must stay BEFORE the IO hop (dialog decisions precede the expensive load).
 *
 * AgentController is not unit-instantiable, so per repo precedent this pins the source text.
 * Slice boundaries: `fun showSession(` → `fun resumeViewedSession(` (do NOT add new functions
 * between these sentinels — see the sentinel-slice trap note in project memory).
 */
class AgentControllerShowSessionOffEdtTest {

    private val src = java.io.File(
        "src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt"
    ).readText()

    private val slice = src
        .substringAfter("fun showSession(sessionId: String) {")
        .substringBefore("fun resumeViewedSession(")

    @Test
    fun `showSession loads ui messages on an IO coroutine, not inline on EDT`() {
        val launchIdx = slice.indexOf("controllerScope.launch(Dispatchers.IO")
        assertTrue(launchIdx >= 0, "showSession must hop to Dispatchers.IO via controllerScope (mirrors showHistory)")

        val loadIdx = slice.indexOf("MessageStateHandler.loadUiMessages(")
        assertTrue(loadIdx >= 0, "showSession must still load ui messages")
        assertTrue(
            loadIdx > launchIdx,
            "loadUiMessages must run INSIDE the IO launch — running it before the hop is the P0-7 EDT freeze"
        )
    }

    @Test
    fun `modal kill-backgrounds decision stays on EDT before the IO hop`() {
        val killIdx = slice.indexOf("killBackgroundsOnTransition(")
        val launchIdx = slice.indexOf("controllerScope.launch(Dispatchers.IO")
        assertTrue(killIdx >= 0, "killBackgroundsOnTransition must remain in showSession")
        assertTrue(
            killIdx < launchIdx,
            "the modal dialog decision must resolve BEFORE the expensive load is dispatched"
        )
    }

    @Test
    fun `webview push happens on EDT via invokeLater after the load`() {
        val launchIdx = slice.indexOf("controllerScope.launch(Dispatchers.IO")
        val invokeIdx = slice.indexOf("invokeLater {")
        val pushIdx = slice.indexOf("pushStateToWebview(")
        assertTrue(invokeIdx > launchIdx, "the EDT push must be inside the IO launch via invokeLater")
        assertTrue(pushIdx > invokeIdx, "pushStateToWebview must run inside the invokeLater block")
    }

    @Test
    fun `serialization runs off-EDT before the push`() {
        val encodeIdx = slice.indexOf("encodeStateForWebview(")
        val invokeIdx = slice.indexOf("invokeLater {")
        assertTrue(encodeIdx >= 0, "showSession must pre-serialize via encodeStateForWebview on IO")
        assertTrue(
            encodeIdx < invokeIdx,
            "the JSON encode must happen on IO, before the invokeLater push — encoding on EDT is half the P0-7 cost"
        )
    }

    @Test
    fun `stale-click guard drops a late push when the viewed session changed`() {
        assertTrue(
            slice.contains("viewedSessionId != sessionId"),
            "the async load must guard against the user having navigated away while loading"
        )
    }
}

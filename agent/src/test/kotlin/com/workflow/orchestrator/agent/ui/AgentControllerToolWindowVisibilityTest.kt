package com.workflow.orchestrator.agent.ui

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Source-contract pins for audit P2-11: webview 1s tickers (UsageIndicator etc.) gate on
 * `document.hidden`, which never flips inside an embedded JCEF browser — the page is
 * "visible" to Chromium even when the Workflow tool window is hidden, so the tickers
 * poll forever.
 *
 * The Kotlin side is the only layer that knows the real visibility, so AgentController
 * wires a [com.intellij.openapi.wm.ex.ToolWindowManagerListener] on the project message
 * bus and pushes the signal into the page:
 *
 *  - `window.__wfToolWindowVisible: boolean` — current visibility of the "Workflow"
 *    tool window (`undefined` until the first transition; consumers treat undefined
 *    as visible).
 *  - `wf-visibility-change` — CustomEvent dispatched on `window` with
 *    `detail: { visible }` after each transition.
 *
 * Webview sources are intentionally NOT modified in this change — landing the signal is
 * the deliverable; a webview-side consumer can be added later against this contract.
 */
class AgentControllerToolWindowVisibilityTest {

    private val src = java.io.File(
        "src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt"
    ).readText()

    @Test
    fun `controller subscribes a ToolWindowManagerListener on the project message bus`() {
        assertTrue(
            src.contains("ToolWindowManagerListener.TOPIC"),
            "AgentController must subscribe to ToolWindowManagerListener.TOPIC for the visibility signal"
        )
        assertTrue(
            src.contains("subscribeToToolWindowVisibility()"),
            "the subscription must be wired from init"
        )
    }

    @Test
    fun `visibility push sets the window global AND dispatches the change event`() {
        assertTrue(
            src.contains("window.__wfToolWindowVisible"),
            "the push must set window.__wfToolWindowVisible"
        )
        assertTrue(
            src.contains("wf-visibility-change"),
            "the push must dispatch the wf-visibility-change CustomEvent"
        )
    }

    @Test
    fun `signal is gated on the Workflow tool window and deduplicated`() {
        val slice = src.substringAfter("private fun subscribeToToolWindowVisibility()")
            .substringBefore("\n    /**")
        assertTrue(
            slice.contains("WORKFLOW_TOOL_WINDOW_ID"),
            "the listener must read the Workflow tool window's visibility (not every tool window event)"
        )
        assertTrue(
            slice.contains("lastVisible"),
            "stateChanged fires for unrelated tool-window changes — pushes must be deduplicated on transition"
        )
    }
}

package com.workflow.orchestrator.pullrequest.ui

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Source-text contract test for B16 (2026-06-10 perf audit).
 *
 * `PrListService.stopPolling()` had ZERO callers — the first PR-tab open started Bitbucket
 * polling that ran for the rest of the IDE session, surviving panel disposal ("Refresh All
 * Tabs" rebuild, project close). This pins the lifecycle wiring:
 *
 * - `PrDashboardPanel.dispose()` stops polling.
 * - `ancestorAdded` (panel re-shown) restarts polling (`startPolling()` is a no-op while the
 *   poll job is still active) before forwarding the visibility signal.
 * - `ancestorRemoved` keeps the existing setVisible(false) behavior (4× background interval
 *   while the tab is merely hidden — full stop is reserved for disposal).
 *
 * Why source-text: PrDashboardPanel needs live IntelliJ tool-window/Swing infrastructure that
 * cannot be instantiated in a plain JUnit 5 environment — the established pattern for these
 * lifecycle invariants in this codebase (see PrListServiceEdtMutationTest).
 */
class PrDashboardPanelPollingLifecycleTest {

    private val src: String by lazy {
        java.io.File(
            "src/main/kotlin/com/workflow/orchestrator/pullrequest/ui/PrDashboardPanel.kt"
        ).readText()
    }

    @Test
    fun `dispose stops PR polling`() {
        val disposeIdx = src.indexOf("override fun dispose()")
        assertTrue(disposeIdx >= 0, "PrDashboardPanel must declare dispose()")
        val disposeBody = src.substring(disposeIdx, src.indexOf("companion object", disposeIdx))
        assertTrue(
            disposeBody.contains("stopPolling()"),
            "dispose() must call PrListService.stopPolling() — B16: polling must not outlive the panel",
        )
    }

    @Test
    fun `ancestorAdded restarts polling before forwarding visibility`() {
        val addedIdx = src.indexOf("override fun ancestorAdded")
        assertTrue(addedIdx >= 0, "visibility listener must implement ancestorAdded")
        val removedIdx = src.indexOf("override fun ancestorRemoved", addedIdx)
        val addedBody = src.substring(addedIdx, removedIdx)
        val startIdx = addedBody.indexOf("startPolling()")
        val visibleIdx = addedBody.indexOf("setVisible(true)")
        assertTrue(startIdx >= 0, "ancestorAdded must restart polling (panel re-shown after a stop)")
        assertTrue(visibleIdx > startIdx, "setVisible(true) must follow the startPolling() restart")
    }

    @Test
    fun `ancestorRemoved keeps visibility slowdown not a full stop`() {
        val removedIdx = src.indexOf("override fun ancestorRemoved")
        assertTrue(removedIdx >= 0)
        val movedIdx = src.indexOf("override fun ancestorMoved", removedIdx)
        val removedBody = src.substring(removedIdx, movedIdx)
        assertTrue(
            removedBody.contains("setVisible(false)") && !removedBody.contains("stopPolling"),
            "tab hide must slow polling (setVisible(false)), not stop it — stop is reserved for dispose()",
        )
    }
}

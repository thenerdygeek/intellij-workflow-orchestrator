package com.workflow.orchestrator.automation.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for the pure P1-21 helpers on [MonitorPanel]:
 *
 * - [MonitorPanel.mergePolledEntries] — the per-poll-cycle batch merge that replaced the old
 *   one-`invokeLater`-per-entry apply loop.
 * - [MonitorPanel.shouldRenderDetail] — the equality gate that skips detail-pane teardown +
 *   rebuild when the rendered model has not changed.
 */
class MonitorPanelCoalesceAndDetailGateTest {

    private fun entry(
        queueId: String,
        status: String = "Running",
        buildNumber: Int = 0,
        stages: List<MonitorPanel.StageInfo> = emptyList(),
        failedTests: Int = 0
    ) = MonitorPanel.RunEntry(
        queueId = queueId,
        suiteName = "Regression",
        planKey = "PROJ-AUTO",
        resultKey = "PROJ-AUTO-7",
        buildNumber = buildNumber,
        status = status,
        stages = stages,
        failedTests = failedTests
    )

    // ------------------------------------------------------------------
    // mergePolledEntries
    // ------------------------------------------------------------------

    @Test
    fun `merge replaces matching entries by queueId and preserves list order`() {
        val current = listOf(entry("a"), entry("b"), entry("c"))
        val updatedB = entry("b", status = "Successful", buildNumber = 12)

        val merged = MonitorPanel.mergePolledEntries(current, listOf(updatedB))

        assertEquals(listOf("a", "b", "c"), merged.map { it.queueId }, "order must be preserved")
        assertEquals("Successful", merged[1].status)
        assertEquals(12, merged[1].buildNumber)
        assertEquals("Running", merged[0].status, "non-updated entries must be untouched")
        assertEquals("Running", merged[2].status, "non-updated entries must be untouched")
    }

    @Test
    fun `merge applies multiple updates from one poll cycle in a single pass`() {
        val current = listOf(entry("a"), entry("b"), entry("c"))
        val updates = listOf(entry("a", status = "Failed"), entry("c", status = "Successful"))

        val merged = MonitorPanel.mergePolledEntries(current, updates)

        assertEquals("Failed", merged[0].status)
        assertEquals("Running", merged[1].status)
        assertEquals("Successful", merged[2].status)
    }

    @Test
    fun `last update wins when the same queueId appears twice in one cycle`() {
        val current = listOf(entry("a"))
        val updates = listOf(entry("a", status = "Queued"), entry("a", status = "Running", buildNumber = 3))

        val merged = MonitorPanel.mergePolledEntries(current, updates)

        assertEquals("Running", merged[0].status)
        assertEquals(3, merged[0].buildNumber)
    }

    @Test
    fun `updates for entries no longer in the list are dropped, not resurrected`() {
        val current = listOf(entry("a"))
        val updates = listOf(entry("gone", status = "Successful"))

        val merged = MonitorPanel.mergePolledEntries(current, updates)

        assertEquals(
            listOf("a"),
            merged.map { it.queueId },
            "a row removed between poll start and apply must not be re-added by a stale update"
        )
    }

    @Test
    fun `empty update batch returns the current list unchanged`() {
        val current = listOf(entry("a"), entry("b"))

        assertSame(
            current,
            MonitorPanel.mergePolledEntries(current, emptyList()),
            "no-op cycles must not allocate a new list"
        )
    }

    // ------------------------------------------------------------------
    // shouldRenderDetail
    // ------------------------------------------------------------------

    @Test
    fun `first render (no previous entry) always renders`() {
        assertTrue(MonitorPanel.shouldRenderDetail(null, entry("a")))
    }

    @Test
    fun `structurally equal entry skips the rebuild even when it is a different instance`() {
        val rendered = entry("a", stages = listOf(MonitorPanel.StageInfo("Build", "Successful", "1m")))
        val freshCopy = rendered.copy()

        assertFalse(
            MonitorPanel.shouldRenderDetail(rendered, freshCopy),
            "data-class equality (not identity) must drive the gate — quiet poll ticks " +
                "produce fresh but identical RunEntry instances"
        )
    }

    @Test
    fun `changed rendered data triggers a rebuild`() {
        val rendered = entry("a", status = "Running")

        assertTrue(MonitorPanel.shouldRenderDetail(rendered, rendered.copy(status = "Successful")))
        assertTrue(MonitorPanel.shouldRenderDetail(rendered, rendered.copy(failedTests = 2)))
        val newStages = rendered.copy(stages = listOf(MonitorPanel.StageInfo("Deploy", "InProgress")))
        assertTrue(MonitorPanel.shouldRenderDetail(rendered, newStages))
    }

    @Test
    fun `selecting a different run always rebuilds`() {
        assertTrue(MonitorPanel.shouldRenderDetail(entry("a"), entry("b")))
    }
}

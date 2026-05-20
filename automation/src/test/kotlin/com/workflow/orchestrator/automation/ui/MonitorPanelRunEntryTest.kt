package com.workflow.orchestrator.automation.ui

import com.workflow.orchestrator.automation.model.QueueEntry
import com.workflow.orchestrator.automation.model.QueueEntryStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Unit tests for the pure `MonitorPanel.buildRunEntry` helper that maps a
 * persisted [QueueEntry] to the UI's [MonitorPanel.RunEntry] view-model.
 *
 * The helper is the only piece of [MonitorPanel] callable without an
 * IntelliJ project / Swing harness, so it carries the load-bearing
 * mapping logic for terminal-status rendering (and, post-fix, the
 * surfacing of `errorMessage` for FAILED_TO_TRIGGER rows).
 */
class MonitorPanelRunEntryTest {

    private fun entry(
        status: QueueEntryStatus,
        errorMessage: String? = null,
        bambooResultKey: String? = null
    ) = QueueEntry(
        id = "x-1",
        suitePlanKey = "PROJ-AUTO",
        dockerTagsPayload = "{}",
        variables = emptyMap(),
        stages = null,
        enqueuedAt = Instant.now(),
        status = status,
        bambooResultKey = bambooResultKey,
        errorMessage = errorMessage
    )

    @Test
    fun `FAILED_TO_TRIGGER row carries errorMessage so the detail panel can render it`() {
        val runEntry = MonitorPanel.buildRunEntry(
            queueEntry = entry(
                status = QueueEntryStatus.FAILED_TO_TRIGGER,
                errorMessage = "Bamboo 401: token expired"
            ),
            suiteName = "Regression",
            bambooUrlBase = ""
        )

        assertEquals("Bamboo 401: token expired", runEntry.errorMessage,
            "Without errorMessage on RunEntry the detail panel cannot tell the user *why* the trigger failed")
        assertEquals(MonitorPanel.MonitorFilter.FAILED, runEntry.filterBucket)
        assertEquals("Failed to trigger", runEntry.status)
    }

    @Test
    fun `non-terminal rows expose errorMessage as null`() {
        val runEntry = MonitorPanel.buildRunEntry(
            queueEntry = entry(status = QueueEntryStatus.WAITING_LOCAL),
            suiteName = "Regression",
            bambooUrlBase = ""
        )

        assertNull(runEntry.errorMessage)
    }
}

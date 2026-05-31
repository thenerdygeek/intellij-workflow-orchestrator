package com.workflow.orchestrator.automation.ui

import com.workflow.orchestrator.automation.model.QueueEntry
import com.workflow.orchestrator.automation.model.QueueEntryStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
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

    // ── AUTOMATION-COV-6: buildRunEntry — remaining status branches ──

    @Test
    fun `COMPLETED maps to filterBucket=COMPLETED, isTerminal=true, and bambooUrl is constructed`() {
        val runEntry = MonitorPanel.buildRunEntry(
            queueEntry = entry(
                status = QueueEntryStatus.COMPLETED,
                bambooResultKey = "PROJ-AUTO-123"
            ),
            suiteName = "Regression",
            bambooUrlBase = "https://bamboo.example.com"
        )

        assertEquals(MonitorPanel.MonitorFilter.COMPLETED, runEntry.filterBucket,
            "COMPLETED must map to filterBucket=COMPLETED")
        assertTrue(runEntry.isTerminal, "COMPLETED must be terminal")
        assertEquals("https://bamboo.example.com/browse/PROJ-AUTO-123", runEntry.bambooUrl,
            "bambooUrl must be constructed as bambooUrlBase/browse/resultKey when both are non-blank")
        assertEquals("Successful", runEntry.status)
    }

    @Test
    fun `FAILED maps to filterBucket=FAILED, isTerminal=true, and forwards errorMessage`() {
        val runEntry = MonitorPanel.buildRunEntry(
            queueEntry = entry(
                status = QueueEntryStatus.FAILED,
                errorMessage = "Stage 'Integration Tests' failed",
                bambooResultKey = "PROJ-AUTO-456"
            ),
            suiteName = "Regression",
            bambooUrlBase = "https://bamboo.example.com"
        )

        assertEquals(MonitorPanel.MonitorFilter.FAILED, runEntry.filterBucket,
            "FAILED must map to filterBucket=FAILED")
        assertTrue(runEntry.isTerminal, "FAILED must be terminal")
        assertEquals("Stage 'Integration Tests' failed", runEntry.errorMessage,
            "FAILED entry must forward errorMessage")
        assertEquals("Failed", runEntry.status)
    }

    @Test
    fun `CANCELLED maps to filterBucket=FAILED (not a dedicated CANCELLED bucket), isTerminal=true`() {
        val runEntry = MonitorPanel.buildRunEntry(
            queueEntry = entry(
                status = QueueEntryStatus.CANCELLED,
                bambooResultKey = "PROJ-AUTO-789"
            ),
            suiteName = "Regression",
            bambooUrlBase = "https://bamboo.example.com"
        )

        assertEquals(MonitorPanel.MonitorFilter.FAILED, runEntry.filterBucket,
            "CANCELLED must bucket under FAILED — there is no CANCELLED filter chip")
        assertTrue(runEntry.isTerminal, "CANCELLED must be terminal")
        assertEquals("Cancelled", runEntry.status)
    }

    @Test
    fun `RUNNING maps to filterBucket=RUNNING and is not terminal`() {
        val runEntry = MonitorPanel.buildRunEntry(
            queueEntry = entry(
                status = QueueEntryStatus.RUNNING,
                bambooResultKey = "PROJ-AUTO-999"
            ),
            suiteName = "Regression",
            bambooUrlBase = "https://bamboo.example.com"
        )

        assertEquals(MonitorPanel.MonitorFilter.RUNNING, runEntry.filterBucket,
            "RUNNING must map to filterBucket=RUNNING")
        assertFalse(runEntry.isTerminal, "RUNNING must not be terminal")
    }

    @Test
    fun `QUEUED_ON_BAMBOO maps to filterBucket=QUEUED and is not terminal`() {
        val runEntry = MonitorPanel.buildRunEntry(
            queueEntry = entry(
                status = QueueEntryStatus.QUEUED_ON_BAMBOO,
                bambooResultKey = "PROJ-AUTO-888"
            ),
            suiteName = "Regression",
            bambooUrlBase = "https://bamboo.example.com"
        )

        assertEquals(MonitorPanel.MonitorFilter.QUEUED, runEntry.filterBucket,
            "QUEUED_ON_BAMBOO must map to filterBucket=QUEUED")
        assertFalse(runEntry.isTerminal, "QUEUED_ON_BAMBOO must not be terminal")
    }

    @Test
    fun `bambooUrl is empty string when bambooUrlBase is blank`() {
        val runEntry = MonitorPanel.buildRunEntry(
            queueEntry = entry(
                status = QueueEntryStatus.COMPLETED,
                bambooResultKey = "PROJ-AUTO-123"
            ),
            suiteName = "Regression",
            bambooUrlBase = ""
        )

        assertEquals("", runEntry.bambooUrl,
            "bambooUrl must be empty when bambooUrlBase is blank")
    }

    @Test
    fun `bambooUrl is empty string when bambooResultKey is null`() {
        val runEntry = MonitorPanel.buildRunEntry(
            queueEntry = entry(
                status = QueueEntryStatus.COMPLETED,
                bambooResultKey = null
            ),
            suiteName = "Regression",
            bambooUrlBase = "https://bamboo.example.com"
        )

        assertEquals("", runEntry.bambooUrl,
            "bambooUrl must be empty when bambooResultKey is null/blank")
    }
}

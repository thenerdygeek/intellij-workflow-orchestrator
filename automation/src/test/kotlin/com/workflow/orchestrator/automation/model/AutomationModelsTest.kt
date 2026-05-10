package com.workflow.orchestrator.automation.model

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant

class AutomationModelsTest {

    @Test
    fun `TagEntry tracks source and registry status`() {
        val entry = TagEntry(
            serviceName = "service-auth",
            currentTag = "feature/PROJ-123-a1b2c3d",
            latestReleaseTag = "2.4.0",
            source = TagSource.AUTO_DETECTED,
            registryStatus = RegistryStatus.VALID,
            isDrift = true,
            isCurrentRepo = true
        )
        assertEquals("service-auth", entry.serviceName)
        assertTrue(entry.isDrift)
        assertTrue(entry.isCurrentRepo)
        assertEquals(TagSource.AUTO_DETECTED, entry.source)
    }

    @Test
    fun `BaselineRun score calculation from constructor`() {
        val run = BaselineRun(
            buildNumber = 847,
            resultKey = "PROJ-AUTO-847",
            dockerTags = mapOf("auth" to "2.4.0", "payments" to "2.3.1"),
            releaseTagCount = 14,
            totalServices = 14,
            successfulStages = 3,
            failedStages = 0,
            triggeredAt = Instant.now(),
            score = 155
        )
        assertEquals(847, run.buildNumber)
        assertEquals(155, run.score)
        assertEquals(2, run.dockerTags.size)
    }

    @Test
    fun `QueueEntry default status is WAITING_LOCAL`() {
        val entry = QueueEntry(
            id = "uuid-1",
            suitePlanKey = "PROJ-AUTO",
            dockerTagsPayload = """{"auth":"2.4.0"}""",
            variables = mapOf("suiteType" to "regression"),
            stages = setOf("QA Automation"),
            enqueuedAt = Instant.now(),
            status = QueueEntryStatus.WAITING_LOCAL,
            bambooResultKey = null
        )
        assertEquals(QueueEntryStatus.WAITING_LOCAL, entry.status)
        assertNull(entry.bambooResultKey)
    }

    @Test
    @Suppress("DEPRECATION")
    fun `QueueEntryStatus has all expected states`() {
        val statuses = QueueEntryStatus.entries
        assertTrue(statuses.contains(QueueEntryStatus.WAITING_LOCAL))
        assertTrue(statuses.contains(QueueEntryStatus.TRIGGERING))
        assertTrue(statuses.contains(QueueEntryStatus.QUEUED_ON_BAMBOO))
        assertTrue(statuses.contains(QueueEntryStatus.RUNNING))
        assertTrue(statuses.contains(QueueEntryStatus.COMPLETED))
        // PR 8: FAILED is distinct from COMPLETED — Bamboo "Failed" outcome
        assertTrue(statuses.contains(QueueEntryStatus.FAILED))
        assertTrue(statuses.contains(QueueEntryStatus.FAILED_TO_TRIGGER))
        // TAG_INVALID is deprecated but retained for pre-L3 SQLite row deserialisation safety
        assertTrue(statuses.contains(QueueEntryStatus.TAG_INVALID))
        assertTrue(statuses.contains(QueueEntryStatus.CANCELLED))
        // PLAN_UNAVAILABLE and STALE were removed — they were never set by QueueService
        assertEquals(9, statuses.size)
    }

    @Test
    @Suppress("DEPRECATION")
    fun `QueueEntryStatus TERMINAL contains exactly the terminal statuses (PR 8)`() {
        // PR 8: terminal entries STAY in QueueService._stateFlow until the user
        // dismisses; this set defines which statuses skip polling and gate the
        // Cancel-vs-Remove button choice in MonitorPanel's detail view.
        val terminal = QueueEntryStatus.TERMINAL
        assertTrue(terminal.contains(QueueEntryStatus.COMPLETED),
            "COMPLETED must be terminal — Bamboo Successful or Unknown/NotBuilt")
        assertTrue(terminal.contains(QueueEntryStatus.FAILED),
            "FAILED must be terminal — Bamboo Failed outcome (PR 8)")
        assertTrue(terminal.contains(QueueEntryStatus.CANCELLED),
            "CANCELLED must be terminal — user-initiated cancel transitions to this state")
        assertTrue(terminal.contains(QueueEntryStatus.FAILED_TO_TRIGGER),
            "FAILED_TO_TRIGGER must be terminal — set when doTrigger fails on Bamboo")
        assertTrue(terminal.contains(QueueEntryStatus.TAG_INVALID),
            "TAG_INVALID must be terminal — pre-L3 SQLite rows must not be re-queued on recovery")

        // Non-terminal active statuses must NOT appear in TERMINAL
        assertFalse(terminal.contains(QueueEntryStatus.WAITING_LOCAL))
        assertFalse(terminal.contains(QueueEntryStatus.TRIGGERING))
        assertFalse(terminal.contains(QueueEntryStatus.QUEUED_ON_BAMBOO))
        assertFalse(terminal.contains(QueueEntryStatus.RUNNING))

        assertEquals(5, terminal.size,
            "TERMINAL should have exactly 5 entries: COMPLETED, FAILED, CANCELLED, FAILED_TO_TRIGGER, TAG_INVALID")
    }

    @Test
    fun `CurrentRepoContext captures detection source`() {
        val ctx = CurrentRepoContext(
            serviceName = "service-auth",
            branchName = "feature/PROJ-123",
            featureBranchTag = "feature/PROJ-123-a1b2c3d",
            detectedFrom = DetectionSource.PROJECT_NAME
        )
        assertEquals(DetectionSource.PROJECT_NAME, ctx.detectedFrom)
        assertEquals("feature/PROJ-123-a1b2c3d", ctx.featureBranchTag)
    }

    @Test
    fun `DriftResult marks stale when versions differ`() {
        val result = DriftResult(
            serviceName = "service-payments",
            currentTag = "2.3.1",
            latestReleaseTag = "2.4.0",
            isStale = true
        )
        assertTrue(result.isStale)
    }

}

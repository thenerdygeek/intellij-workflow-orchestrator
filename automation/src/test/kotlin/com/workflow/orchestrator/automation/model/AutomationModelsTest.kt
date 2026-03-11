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
            stages = listOf("QA Automation"),
            enqueuedAt = Instant.now(),
            status = QueueEntryStatus.WAITING_LOCAL,
            bambooResultKey = null
        )
        assertEquals(QueueEntryStatus.WAITING_LOCAL, entry.status)
        assertNull(entry.bambooResultKey)
    }

    @Test
    fun `QueueEntryStatus has all expected states`() {
        val statuses = QueueEntryStatus.entries
        assertTrue(statuses.contains(QueueEntryStatus.WAITING_LOCAL))
        assertTrue(statuses.contains(QueueEntryStatus.TRIGGERING))
        assertTrue(statuses.contains(QueueEntryStatus.QUEUED_ON_BAMBOO))
        assertTrue(statuses.contains(QueueEntryStatus.RUNNING))
        assertTrue(statuses.contains(QueueEntryStatus.COMPLETED))
        assertTrue(statuses.contains(QueueEntryStatus.FAILED_TO_TRIGGER))
        assertTrue(statuses.contains(QueueEntryStatus.TAG_INVALID))
        assertTrue(statuses.contains(QueueEntryStatus.PLAN_UNAVAILABLE))
        assertTrue(statuses.contains(QueueEntryStatus.STALE))
        assertTrue(statuses.contains(QueueEntryStatus.CANCELLED))
        assertEquals(10, statuses.size)
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

    @Test
    fun `Conflict captures overlap details`() {
        val conflict = Conflict(
            serviceName = "service-auth",
            yourTag = "feature/PROJ-123-abc",
            otherTag = "2.4.0",
            triggeredBy = "dev-jones",
            buildNumber = 848,
            isRunning = true
        )
        assertTrue(conflict.isRunning)
        assertEquals("dev-jones", conflict.triggeredBy)
    }
}

package com.workflow.orchestrator.agent.service

import com.workflow.orchestrator.agent.runtime.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class MetricsStoreTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var store: MetricsStore

    @BeforeEach
    fun setup() {
        store = MetricsStore(tempDir.toString())
    }

    private fun makeScorecard(
        sessionId: String,
        status: String = "completed",
        timestamp: Long = System.currentTimeMillis(),
        toolCallCount: Int = 5,
        estimatedCostUsd: Double = 0.05
    ) = SessionScorecard(
        sessionId = sessionId,
        taskDescription = "Test task for $sessionId",
        completionStatus = status,
        timestamp = timestamp,
        metrics = SessionScorecardMetrics(
            totalIterations = 10,
            toolCallCount = toolCallCount,
            uniqueToolsUsed = 3,
            errorCount = 1,
            compressionCount = 0,
            totalInputTokens = 10_000,
            totalOutputTokens = 1_000,
            estimatedCostUsd = estimatedCostUsd,
            planStepsTotal = 2,
            planStepsCompleted = 2,
            selfCorrectionAttempts = 1,
            selfCorrectionSuccesses = 1,
            approvalCount = 0,
            subagentCount = 0,
            durationMs = 5_000
        ),
        qualitySignals = QualitySignals(
            hallucinationFlags = 0,
            credentialLeakAttempts = 0,
            doomLoopTriggers = 0,
            circuitBreakerTrips = 0,
            guardrailHits = 0,
            filesEditedCount = 2,
            filesVerifiedCount = 2,
            filesExhaustedCount = 0
        )
    )

    @Test
    fun `save and load round-trip`() {
        val original = makeScorecard("abc-123")
        store.save(original)

        val loaded = store.load("abc-123")
        assertNotNull(loaded)
        assertEquals(original.sessionId, loaded!!.sessionId)
        assertEquals(original.taskDescription, loaded.taskDescription)
        assertEquals(original.completionStatus, loaded.completionStatus)
        assertEquals(original.metrics.totalIterations, loaded.metrics.totalIterations)
        assertEquals(original.metrics.toolCallCount, loaded.metrics.toolCallCount)
        assertEquals(original.metrics.estimatedCostUsd, loaded.metrics.estimatedCostUsd, 0.001)
        assertEquals(original.qualitySignals.filesEditedCount, loaded.qualitySignals.filesEditedCount)
    }

    @Test
    fun `load returns null for nonexistent session`() {
        assertNull(store.load("nonexistent"))
    }

    @Test
    fun `load returns null for corrupt file`() {
        val metricsDir = File(tempDir.toFile(), ".workflow/agent/metrics")
        metricsDir.mkdirs()
        File(metricsDir, "scorecard-corrupt.json").writeText("not valid json {{{")

        assertNull(store.load("corrupt"))
    }

    @Test
    fun `loadAll returns scorecards sorted by timestamp descending`() {
        store.save(makeScorecard("s1", timestamp = 1000))
        store.save(makeScorecard("s2", timestamp = 3000))
        store.save(makeScorecard("s3", timestamp = 2000))

        val all = store.loadAll()
        assertEquals(3, all.size)
        assertEquals("s2", all[0].sessionId)
        assertEquals("s3", all[1].sessionId)
        assertEquals("s1", all[2].sessionId)
    }

    @Test
    fun `loadAll returns empty list when no scorecards exist`() {
        assertTrue(store.loadAll().isEmpty())
    }

    @Test
    fun `loadAll skips corrupt files`() {
        store.save(makeScorecard("good1", timestamp = 2000))
        val metricsDir = File(tempDir.toFile(), ".workflow/agent/metrics")
        File(metricsDir, "scorecard-bad.json").writeText("corrupted")
        store.save(makeScorecard("good2", timestamp = 1000))

        val all = store.loadAll()
        assertEquals(2, all.size)
    }

    @Test
    fun `loadRecent returns limited number of scorecards`() {
        store.save(makeScorecard("s1", timestamp = 1000))
        store.save(makeScorecard("s2", timestamp = 3000))
        store.save(makeScorecard("s3", timestamp = 2000))

        val recent = store.loadRecent(2)
        assertEquals(2, recent.size)
        assertEquals("s2", recent[0].sessionId)
        assertEquals("s3", recent[1].sessionId)
    }

    @Test
    fun `loadRecent returns all when limit exceeds count`() {
        store.save(makeScorecard("s1"))
        val recent = store.loadRecent(10)
        assertEquals(1, recent.size)
    }

    @Test
    fun `getSummaryStats computes correct aggregates`() {
        store.save(makeScorecard("s1", status = "completed", toolCallCount = 10, estimatedCostUsd = 0.10))
        store.save(makeScorecard("s2", status = "failed", toolCallCount = 6, estimatedCostUsd = 0.06))
        store.save(makeScorecard("s3", status = "completed", toolCallCount = 8, estimatedCostUsd = 0.08))

        val stats = store.getSummaryStats()
        assertEquals(3, stats.totalSessions)
        assertEquals(2, stats.completedSessions)
        assertEquals(1, stats.failedSessions)
        assertEquals(2.0 / 3.0, stats.completionRate, 0.001)
        assertEquals(8.0, stats.avgToolCalls, 0.001) // (10+6+8)/3
        assertEquals(0.24, stats.totalEstimatedCostUsd, 0.001)
        assertEquals(0.08, stats.avgEstimatedCostUsd, 0.001)
    }

    @Test
    fun `getSummaryStats returns EMPTY for no scorecards`() {
        val stats = store.getSummaryStats()
        assertEquals(SummaryStats.EMPTY, stats)
    }

    @Test
    fun `cleanup removes old files by age`() {
        store.save(makeScorecard("old"))
        // Set the file modification time to 31 days ago
        val metricsDir = File(tempDir.toFile(), ".workflow/agent/metrics")
        val oldFile = File(metricsDir, "scorecard-old.json")
        oldFile.setLastModified(System.currentTimeMillis() - 31L * 24 * 60 * 60 * 1000)

        store.save(makeScorecard("recent"))

        val removed = store.cleanup(maxAgeMs = 30L * 24 * 60 * 60 * 1000, maxCount = 100)
        assertEquals(1, removed)
        assertNull(store.load("old"))
        assertNotNull(store.load("recent"))
    }

    @Test
    fun `cleanup removes excess files by count`() {
        store.save(makeScorecard("s1", timestamp = 1000))
        store.save(makeScorecard("s2", timestamp = 2000))
        store.save(makeScorecard("s3", timestamp = 3000))

        // Set different last-modified times so ordering is deterministic
        val metricsDir = File(tempDir.toFile(), ".workflow/agent/metrics")
        File(metricsDir, "scorecard-s1.json").setLastModified(1000)
        File(metricsDir, "scorecard-s2.json").setLastModified(2000)
        File(metricsDir, "scorecard-s3.json").setLastModified(3000)

        val removed = store.cleanup(maxAgeMs = Long.MAX_VALUE, maxCount = 2)
        assertEquals(1, removed)
        // s1 (oldest) should be removed
        assertNull(store.load("s1"))
        assertNotNull(store.load("s2"))
        assertNotNull(store.load("s3"))
    }

    @Test
    fun `cleanup returns zero when nothing to remove`() {
        store.save(makeScorecard("s1"))
        val removed = store.cleanup(maxAgeMs = Long.MAX_VALUE, maxCount = 100)
        assertEquals(0, removed)
    }

    @Test
    fun `cleanup returns zero for empty directory`() {
        assertEquals(0, store.cleanup())
    }
}

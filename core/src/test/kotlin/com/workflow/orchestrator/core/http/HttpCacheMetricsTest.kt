package com.workflow.orchestrator.core.http

import com.workflow.orchestrator.core.http.HttpCacheMetrics.Outcome
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HttpCacheMetricsTest {

    @AfterEach
    fun reset() {
        HttpCacheMetrics.reset()
    }

    @Test
    fun `unknown tag returns all-zero stats`() {
        val stats = HttpCacheMetrics.getStats("unknown")
        assertEquals(0L, stats.total)
        assertEquals(0.0, stats.hitRatePct)
        assertEquals(0L, stats.bytesInCache)
    }

    @Test
    fun `record counts the right bucket`() {
        HttpCacheMetrics.record("jira", Outcome.HIT_FRESH)
        HttpCacheMetrics.record("jira", Outcome.HIT_FRESH)
        HttpCacheMetrics.record("jira", Outcome.HIT_STALE_MATCH)
        HttpCacheMetrics.record("jira", Outcome.MISS)

        val stats = HttpCacheMetrics.getStats("jira")
        assertEquals(2L, stats.hitFresh)
        assertEquals(1L, stats.hitStaleMatch)
        assertEquals(0L, stats.hitStaleDiffer)
        assertEquals(1L, stats.miss)
        assertEquals(4L, stats.total)
    }

    @Test
    fun `hit rate computes across all hit categories`() {
        repeat(3) { HttpCacheMetrics.record("bamboo", Outcome.HIT_FRESH) }
        repeat(2) { HttpCacheMetrics.record("bamboo", Outcome.HIT_STALE_MATCH) }
        HttpCacheMetrics.record("bamboo", Outcome.HIT_STALE_DIFFER)
        repeat(4) { HttpCacheMetrics.record("bamboo", Outcome.MISS) }

        val stats = HttpCacheMetrics.getStats("bamboo")
        assertEquals(10L, stats.total)
        assertEquals(6L, stats.hits)
        assertEquals(60.0, stats.hitRatePct)
    }

    @Test
    fun `tags are isolated`() {
        HttpCacheMetrics.record("jira", Outcome.HIT_FRESH)
        HttpCacheMetrics.record("bamboo", Outcome.MISS)

        assertEquals(1L, HttpCacheMetrics.getStats("jira").hitFresh)
        assertEquals(0L, HttpCacheMetrics.getStats("jira").miss)
        assertEquals(0L, HttpCacheMetrics.getStats("bamboo").hitFresh)
        assertEquals(1L, HttpCacheMetrics.getStats("bamboo").miss)
    }

    @Test
    fun `evictions and mutation invalidations are separate counters`() {
        HttpCacheMetrics.recordEviction("jira")
        HttpCacheMetrics.recordEviction("jira")
        HttpCacheMetrics.recordMutationInvalidation("jira")

        val stats = HttpCacheMetrics.getStats("jira")
        assertEquals(2L, stats.evicted)
        assertEquals(1L, stats.invalidatedByMutation)
        assertEquals(0L, stats.total)
    }

    @Test
    fun `byte and entry counters accept negative deltas`() {
        HttpCacheMetrics.updateBytes("jira", 1024L)
        HttpCacheMetrics.updateBytes("jira", 512L)
        HttpCacheMetrics.updateBytes("jira", -200L)
        HttpCacheMetrics.updateEntries("jira", 3L)
        HttpCacheMetrics.updateEntries("jira", -1L)

        val stats = HttpCacheMetrics.getStats("jira")
        assertEquals(1336L, stats.bytesInCache)
        assertEquals(2L, stats.entriesInCache)
    }

    @Test
    fun `getAllStats returns every tag that has any data`() {
        HttpCacheMetrics.record("jira", Outcome.HIT_FRESH)
        HttpCacheMetrics.updateBytes("bamboo", 500L)
        HttpCacheMetrics.recordEviction("sonar")

        val all = HttpCacheMetrics.getAllStats()
        assertTrue(all.containsKey("jira"))
        assertTrue(all.containsKey("bamboo"))
        assertTrue(all.containsKey("sonar"))
        assertEquals(1L, all.getValue("jira").hitFresh)
        assertEquals(500L, all.getValue("bamboo").bytesInCache)
        assertEquals(1L, all.getValue("sonar").evicted)
    }

    @Test
    fun `reset clears all counters across all tags`() {
        HttpCacheMetrics.record("jira", Outcome.HIT_FRESH)
        HttpCacheMetrics.updateBytes("bamboo", 500L)

        HttpCacheMetrics.reset()

        assertEquals(0L, HttpCacheMetrics.getStats("jira").hitFresh)
        assertEquals(0L, HttpCacheMetrics.getStats("bamboo").bytesInCache)
        assertTrue(HttpCacheMetrics.getAllStats().isEmpty())
    }

    @Test
    fun `hit rate is zero when no requests recorded`() {
        val stats = HttpCacheMetrics.getStats("empty")
        assertEquals(0.0, stats.hitRatePct)
    }
}

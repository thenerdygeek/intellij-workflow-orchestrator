package com.workflow.orchestrator.agent.observability

import com.workflow.orchestrator.agent.tools.CompletionKind
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class SessionMetricsTest {

    // ── Empty state ──────────────────────────────────────────────────

    @Nested
    inner class EmptyMetrics {

        @Test
        fun `snapshot of fresh metrics has all zeros`() {
            val metrics = SessionMetrics()
            val snap = metrics.snapshot()

            assertEquals(0, snap.totalToolCalls)
            assertEquals(0, snap.failedToolCalls)
            assertTrue(snap.toolStats.isEmpty())
            assertEquals(0, snap.apiCalls)
            assertEquals(0L, snap.avgApiLatencyMs)
            assertEquals(0, snap.totalPromptTokens)
            assertEquals(0, snap.totalCompletionTokens)
            assertEquals(0, snap.compactionCount)
            assertTrue(snap.completionKindCounts.isEmpty())
        }
    }

    // ── Completion kind recording ────────────────────────────────────

    @Nested
    inner class CompletionKindRecording {

        @Test
        fun `single done completion is counted`() {
            val metrics = SessionMetrics()
            metrics.recordCompletion(CompletionKind.DONE)

            val snap = metrics.snapshot()
            assertEquals(1, snap.completionKindCounts["done"])
        }

        @Test
        fun `all three kinds are counted independently`() {
            val metrics = SessionMetrics()
            metrics.recordCompletion(CompletionKind.DONE)
            metrics.recordCompletion(CompletionKind.DONE)
            metrics.recordCompletion(CompletionKind.REVIEW)
            metrics.recordCompletion(CompletionKind.HEADS_UP)

            val snap = metrics.snapshot()
            assertEquals(2, snap.completionKindCounts["done"])
            assertEquals(1, snap.completionKindCounts["review"])
            assertEquals(1, snap.completionKindCounts["heads_up"])
        }

        @Test
        fun `completionKindCounts serializes and deserializes correctly`() {
            val metrics = SessionMetrics()
            metrics.recordCompletion(CompletionKind.REVIEW)
            metrics.recordCompletion(CompletionKind.HEADS_UP)

            val snap = metrics.snapshot()
            val json = Json.encodeToString(snap)
            val decoded = Json.decodeFromString<SessionMetrics.MetricsSnapshot>(json)

            assertEquals(snap.completionKindCounts, decoded.completionKindCounts)
            assertEquals(1, decoded.completionKindCounts["review"])
            assertEquals(1, decoded.completionKindCounts["heads_up"])
        }
    }

    // ── Tool call recording ──────────────────────────────────────────

    @Nested
    inner class ToolCallRecording {

        @Test
        fun `single successful tool call produces correct stats`() {
            val metrics = SessionMetrics()
            metrics.recordToolCall("edit_file", 100L, isError = false)

            val snap = metrics.snapshot()
            assertEquals(1, snap.totalToolCalls)
            assertEquals(0, snap.failedToolCalls)

            val stats = snap.toolStats["edit_file"]!!
            assertEquals(1, stats.count)
            assertEquals(100L, stats.avgMs)
            assertEquals(100L, stats.minMs)
            assertEquals(100L, stats.maxMs)
            assertEquals(0, stats.errors)
        }

        @Test
        fun `single failed tool call increments error counts`() {
            val metrics = SessionMetrics()
            metrics.recordToolCall("bash", 50L, isError = true)

            val snap = metrics.snapshot()
            assertEquals(1, snap.totalToolCalls)
            assertEquals(1, snap.failedToolCalls)

            val stats = snap.toolStats["bash"]!!
            assertEquals(1, stats.count)
            assertEquals(1, stats.errors)
        }

        @Test
        fun `multiple calls compute correct avg min max`() {
            val metrics = SessionMetrics()
            metrics.recordToolCall("read_file", 10L, isError = false)
            metrics.recordToolCall("read_file", 30L, isError = false)
            metrics.recordToolCall("read_file", 20L, isError = false)

            val snap = metrics.snapshot()
            assertEquals(3, snap.totalToolCalls)

            val stats = snap.toolStats["read_file"]!!
            assertEquals(3, stats.count)
            assertEquals(20L, stats.avgMs)   // (10+30+20)/3
            assertEquals(10L, stats.minMs)
            assertEquals(30L, stats.maxMs)
            assertEquals(0, stats.errors)
        }

        @Test
        fun `mixed success and failure calls tracked per tool`() {
            val metrics = SessionMetrics()
            metrics.recordToolCall("bash", 100L, isError = false)
            metrics.recordToolCall("bash", 200L, isError = true)
            metrics.recordToolCall("bash", 300L, isError = false)

            val snap = metrics.snapshot()
            assertEquals(3, snap.totalToolCalls)
            assertEquals(1, snap.failedToolCalls)

            val stats = snap.toolStats["bash"]!!
            assertEquals(3, stats.count)
            assertEquals(200L, stats.avgMs)   // (100+200+300)/3
            assertEquals(100L, stats.minMs)
            assertEquals(300L, stats.maxMs)
            assertEquals(1, stats.errors)
        }

        @Test
        fun `multiple distinct tools tracked independently`() {
            val metrics = SessionMetrics()
            metrics.recordToolCall("edit_file", 100L, isError = false)
            metrics.recordToolCall("read_file", 50L, isError = false)
            metrics.recordToolCall("edit_file", 200L, isError = true)

            val snap = metrics.snapshot()
            assertEquals(3, snap.totalToolCalls)
            assertEquals(1, snap.failedToolCalls)
            assertEquals(2, snap.toolStats.size)

            val editStats = snap.toolStats["edit_file"]!!
            assertEquals(2, editStats.count)
            assertEquals(150L, editStats.avgMs)
            assertEquals(1, editStats.errors)

            val readStats = snap.toolStats["read_file"]!!
            assertEquals(1, readStats.count)
            assertEquals(0, readStats.errors)
        }
    }

    // ── API call recording ───────────────────────────────────────────

    @Nested
    inner class ApiCallRecording {

        @Test
        fun `single API call recorded correctly`() {
            val metrics = SessionMetrics()
            metrics.recordApiCall(latencyMs = 400L, promptTokens = 1000, completionTokens = 200)

            val snap = metrics.snapshot()
            assertEquals(1, snap.apiCalls)
            assertEquals(400L, snap.avgApiLatencyMs)
            assertEquals(1000, snap.totalPromptTokens)
            assertEquals(200, snap.totalCompletionTokens)
        }

        @Test
        fun `multiple API calls accumulate tokens and average latency`() {
            val metrics = SessionMetrics()
            metrics.recordApiCall(latencyMs = 200L, promptTokens = 500, completionTokens = 100)
            metrics.recordApiCall(latencyMs = 600L, promptTokens = 300, completionTokens = 150)

            val snap = metrics.snapshot()
            assertEquals(2, snap.apiCalls)
            assertEquals(400L, snap.avgApiLatencyMs)    // (200+600)/2
            assertEquals(800, snap.totalPromptTokens)   // 500+300
            assertEquals(250, snap.totalCompletionTokens) // 100+150
        }

        @Test
        fun `three API calls average latency is correct`() {
            val metrics = SessionMetrics()
            metrics.recordApiCall(100L, 100, 10)
            metrics.recordApiCall(200L, 200, 20)
            metrics.recordApiCall(300L, 300, 30)

            val snap = metrics.snapshot()
            assertEquals(3, snap.apiCalls)
            assertEquals(200L, snap.avgApiLatencyMs)    // (100+200+300)/3
            assertEquals(600, snap.totalPromptTokens)
            assertEquals(60, snap.totalCompletionTokens)
        }
    }

    // ── Compaction recording ─────────────────────────────────────────

    @Nested
    inner class CompactionRecording {

        @Test
        fun `single compaction event increments count`() {
            val metrics = SessionMetrics()
            metrics.recordCompaction(tokensBefore = 180_000, tokensAfter = 60_000)

            val snap = metrics.snapshot()
            assertEquals(1, snap.compactionCount)
        }

        @Test
        fun `multiple compactions accumulate count`() {
            val metrics = SessionMetrics()
            metrics.recordCompaction(180_000, 60_000)
            metrics.recordCompaction(160_000, 55_000)
            metrics.recordCompaction(170_000, 58_000)

            val snap = metrics.snapshot()
            assertEquals(3, snap.compactionCount)
        }
    }

    // ── Snapshot immutability and serialization ──────────────────────

    @Nested
    inner class SnapshotSerialization {

        @Test
        fun `snapshot is serializable to JSON and back`() {
            val metrics = SessionMetrics()
            metrics.recordToolCall("edit_file", 120L, isError = false)
            metrics.recordToolCall("bash", 80L, isError = true)
            metrics.recordApiCall(350L, 1200, 300)
            metrics.recordCompaction(170_000, 60_000)

            val snap = metrics.snapshot()
            val json = Json.encodeToString(snap)

            assertTrue(json.isNotBlank())
            assertTrue(json.contains("edit_file"))
            assertTrue(json.contains("totalToolCalls"))

            val decoded = Json.decodeFromString<SessionMetrics.MetricsSnapshot>(json)
            assertEquals(snap.totalToolCalls, decoded.totalToolCalls)
            assertEquals(snap.failedToolCalls, decoded.failedToolCalls)
            assertEquals(snap.apiCalls, decoded.apiCalls)
            assertEquals(snap.avgApiLatencyMs, decoded.avgApiLatencyMs)
            assertEquals(snap.totalPromptTokens, decoded.totalPromptTokens)
            assertEquals(snap.totalCompletionTokens, decoded.totalCompletionTokens)
            assertEquals(snap.compactionCount, decoded.compactionCount)
            assertEquals(snap.toolStats.size, decoded.toolStats.size)

            val editStats = decoded.toolStats["edit_file"]!!
            assertEquals(1, editStats.count)
            assertEquals(120L, editStats.avgMs)
            assertEquals(0, editStats.errors)
        }

        @Test
        fun `empty snapshot serializes and deserializes cleanly`() {
            val snap = SessionMetrics().snapshot()
            val json = Json.encodeToString(snap)
            val decoded = Json.decodeFromString<SessionMetrics.MetricsSnapshot>(json)

            assertEquals(0, decoded.totalToolCalls)
            assertEquals(0, decoded.compactionCount)
            assertTrue(decoded.toolStats.isEmpty())
        }

        @Test
        fun `successive snapshots are independent`() {
            val metrics = SessionMetrics()
            val snap1 = metrics.snapshot()
            metrics.recordToolCall("bash", 50L, isError = false)
            val snap2 = metrics.snapshot()

            assertEquals(0, snap1.totalToolCalls)
            assertEquals(1, snap2.totalToolCalls)
        }
    }

    // ── Thread safety (basic smoke test) ────────────────────────────

    @Nested
    inner class ThreadSafety {

        @Test
        fun `concurrent tool call recording produces consistent total count`() {
            val metrics = SessionMetrics()
            val threads = (1..10).map { i ->
                Thread {
                    repeat(100) {
                        metrics.recordToolCall("tool_$i", 10L, isError = false)
                    }
                }
            }
            threads.forEach { it.start() }
            threads.forEach { it.join() }

            val snap = metrics.snapshot()
            assertEquals(1000, snap.totalToolCalls)
        }
    }
}

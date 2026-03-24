package com.workflow.orchestrator.agent.runtime

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AgentMetricsTest {

    private lateinit var metrics: AgentMetrics

    @BeforeEach
    fun setup() {
        metrics = AgentMetrics()
    }

    // --- Per-tool metrics ---

    @Test
    fun `records tool call metric`() {
        metrics.recordToolCall("read_file", durationMs = 50, success = true, tokens = 100)
        val snapshot = metrics.snapshot()
        assertEquals(1, snapshot.toolCalls["read_file"]?.callCount)
        assertEquals(50L, snapshot.toolCalls["read_file"]?.totalDurationMs)
        assertEquals(100L, snapshot.toolCalls["read_file"]?.totalTokens)
        assertEquals(0, snapshot.toolCalls["read_file"]?.errorCount)
    }

    @Test
    fun `accumulates multiple calls for same tool`() {
        metrics.recordToolCall("read_file", durationMs = 50, success = true, tokens = 100)
        metrics.recordToolCall("read_file", durationMs = 30, success = true, tokens = 80)
        val snapshot = metrics.snapshot()
        assertEquals(2, snapshot.toolCalls["read_file"]?.callCount)
        assertEquals(80L, snapshot.toolCalls["read_file"]?.totalDurationMs)
        assertEquals(180L, snapshot.toolCalls["read_file"]?.totalTokens)
    }

    @Test
    fun `tracks errors separately`() {
        metrics.recordToolCall("edit_file", durationMs = 10, success = true, tokens = 50)
        metrics.recordToolCall("edit_file", durationMs = 5, success = false, tokens = 10)
        val snapshot = metrics.snapshot()
        assertEquals(2, snapshot.toolCalls["edit_file"]?.callCount)
        assertEquals(1, snapshot.toolCalls["edit_file"]?.errorCount)
    }

    @Test
    fun `tracks multiple tools independently`() {
        metrics.recordToolCall("read_file", durationMs = 50, success = true, tokens = 100)
        metrics.recordToolCall("edit_file", durationMs = 30, success = true, tokens = 80)
        metrics.recordToolCall("search_code", durationMs = 20, success = false, tokens = 0)
        val snapshot = metrics.snapshot()
        assertEquals(3, snapshot.toolCalls.size)
        assertTrue(snapshot.toolCalls.containsKey("read_file"))
        assertTrue(snapshot.toolCalls.containsKey("edit_file"))
        assertTrue(snapshot.toolCalls.containsKey("search_code"))
    }

    // --- Circuit breaker ---

    @Test
    fun `circuit breaker trips after 5 consecutive failures`() {
        repeat(5) {
            metrics.recordToolCall("edit_file", durationMs = 10, success = false, tokens = 0)
        }
        assertTrue(metrics.isCircuitBroken("edit_file"))
    }

    @Test
    fun `circuit breaker does not trip with fewer than 5 failures`() {
        repeat(4) {
            metrics.recordToolCall("edit_file", durationMs = 10, success = false, tokens = 0)
        }
        assertFalse(metrics.isCircuitBroken("edit_file"))
    }

    @Test
    fun `success resets consecutive error count`() {
        repeat(4) {
            metrics.recordToolCall("edit_file", durationMs = 10, success = false, tokens = 0)
        }
        metrics.recordToolCall("edit_file", durationMs = 10, success = true, tokens = 50)
        assertFalse(metrics.isCircuitBroken("edit_file"))
        assertEquals(0, metrics.consecutiveErrors("edit_file"))
    }

    @Test
    fun `circuit breaker is per-tool`() {
        repeat(5) {
            metrics.recordToolCall("edit_file", durationMs = 10, success = false, tokens = 0)
        }
        assertTrue(metrics.isCircuitBroken("edit_file"))
        assertFalse(metrics.isCircuitBroken("read_file"))
    }

    @Test
    fun `isCircuitBroken returns false for unknown tool`() {
        assertFalse(metrics.isCircuitBroken("nonexistent_tool"))
    }

    @Test
    fun `consecutiveErrors returns 0 for unknown tool`() {
        assertEquals(0, metrics.consecutiveErrors("nonexistent_tool"))
    }

    // --- Session-level counters ---

    @Test
    fun `session counters default to zero`() {
        val snapshot = metrics.snapshot()
        assertEquals(0, snapshot.turnCount)
        assertEquals(0, snapshot.compressionCount)
        assertEquals(0, snapshot.approvalCount)
        assertEquals(0, snapshot.subagentCount)
    }

    @Test
    fun `session counters are tracked`() {
        metrics.turnCount = 5
        metrics.compressionCount = 2
        metrics.approvalCount = 3
        metrics.subagentCount = 1
        val snapshot = metrics.snapshot()
        assertEquals(5, snapshot.turnCount)
        assertEquals(2, snapshot.compressionCount)
        assertEquals(3, snapshot.approvalCount)
        assertEquals(1, snapshot.subagentCount)
    }

    // --- Snapshot ---

    @Test
    fun `snapshot calculates total tokens across all tools`() {
        metrics.recordToolCall("read_file", durationMs = 50, success = true, tokens = 100)
        metrics.recordToolCall("edit_file", durationMs = 30, success = true, tokens = 200)
        metrics.recordToolCall("search_code", durationMs = 20, success = true, tokens = 50)
        val snapshot = metrics.snapshot()
        assertEquals(350L, snapshot.totalTokens)
    }

    @Test
    fun `snapshot has non-negative duration`() {
        Thread.sleep(10)
        val snapshot = metrics.snapshot()
        assertTrue(snapshot.durationMs >= 0)
    }

    @Test
    fun `snapshot is a frozen copy`() {
        metrics.recordToolCall("read_file", durationMs = 50, success = true, tokens = 100)
        val snapshot1 = metrics.snapshot()
        metrics.recordToolCall("read_file", durationMs = 30, success = true, tokens = 80)
        val snapshot2 = metrics.snapshot()
        assertEquals(1, snapshot1.toolCalls["read_file"]?.callCount)
        assertEquals(2, snapshot2.toolCalls["read_file"]?.callCount)
    }

    // --- JSON serialization ---

    @Test
    fun `toJson produces valid JSON`() {
        metrics.recordToolCall("read_file", durationMs = 50, success = true, tokens = 100)
        metrics.turnCount = 3
        val jsonStr = metrics.toJson()
        assertNotNull(jsonStr)
        assertTrue(jsonStr.contains("read_file"))
        assertTrue(jsonStr.contains("turnCount"))
        // Verify it parses without error
        val parsed = Json { ignoreUnknownKeys = true }.decodeFromString<SessionSnapshot>(jsonStr)
        assertEquals(1, parsed.toolCalls["read_file"]?.callCount)
        assertEquals(3, parsed.turnCount)
    }

    @Test
    fun `empty metrics serializes to valid JSON`() {
        val jsonStr = metrics.toJson()
        assertNotNull(jsonStr)
        val parsed = Json { ignoreUnknownKeys = true }.decodeFromString<SessionSnapshot>(jsonStr)
        assertTrue(parsed.toolCalls.isEmpty())
        assertEquals(0, parsed.turnCount)
    }
}

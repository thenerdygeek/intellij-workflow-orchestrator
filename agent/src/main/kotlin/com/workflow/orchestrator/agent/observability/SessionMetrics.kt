package com.workflow.orchestrator.agent.observability

import kotlinx.serialization.Serializable

/**
 * Per-session metrics accumulator — tracks tool execution times, API latencies,
 * success/failure counts, and compaction events across a single agent session.
 *
 * All mutating methods are @Synchronized for thread-safety. Internal state is kept
 * as simple mutable lists of raw records; derived stats (avg/min/max) are computed
 * lazily in [snapshot] to keep recording paths cheap.
 *
 * [snapshot] produces an immutable, fully @Serializable [MetricsSnapshot] suitable
 * for embedding in the persisted Session JSON.
 */
class SessionMetrics {

    // ── Internal record types (private, not serialized) ─────────────────────

    private data class ToolRecord(
        val toolName: String,
        val durationMs: Long,
        val isError: Boolean
    )

    private data class ApiRecord(
        val latencyMs: Long,
        val promptTokens: Int,
        val completionTokens: Int
    )

    private data class CompactionRecord(
        val tokensBefore: Int,
        val tokensAfter: Int
    )

    // ── Mutable backing state ────────────────────────────────────────────────

    private val toolRecords = mutableListOf<ToolRecord>()
    private val apiRecords = mutableListOf<ApiRecord>()
    private val compactionRecords = mutableListOf<CompactionRecord>()

    // ── Public recording methods ─────────────────────────────────────────────

    @Synchronized
    fun recordToolCall(toolName: String, durationMs: Long, isError: Boolean) {
        toolRecords.add(ToolRecord(toolName, durationMs, isError))
    }

    @Synchronized
    fun recordApiCall(latencyMs: Long, promptTokens: Int, completionTokens: Int) {
        apiRecords.add(ApiRecord(latencyMs, promptTokens, completionTokens))
    }

    @Synchronized
    fun recordCompaction(tokensBefore: Int, tokensAfter: Int) {
        compactionRecords.add(CompactionRecord(tokensBefore, tokensAfter))
    }

    /**
     * Returns an immutable snapshot of all accumulated metrics.
     *
     * Safe to call at any time — takes a consistent point-in-time view under
     * the same lock used by the recording methods.
     */
    @Synchronized
    fun snapshot(): MetricsSnapshot {
        val toolStatsByName: Map<String, ToolStats> = toolRecords
            .groupBy { it.toolName }
            .mapValues { (_, records) ->
                val durations = records.map { it.durationMs }
                ToolStats(
                    count = records.size,
                    avgMs = durations.average().toLong(),
                    minMs = durations.min(),
                    maxMs = durations.max(),
                    errors = records.count { it.isError }
                )
            }

        val apiCount = apiRecords.size
        val avgApiLatency = if (apiCount == 0) 0L
        else apiRecords.map { it.latencyMs }.average().toLong()

        return MetricsSnapshot(
            totalToolCalls = toolRecords.size,
            failedToolCalls = toolRecords.count { it.isError },
            toolStats = toolStatsByName,
            apiCalls = apiCount,
            avgApiLatencyMs = avgApiLatency,
            totalPromptTokens = apiRecords.sumOf { it.promptTokens },
            totalCompletionTokens = apiRecords.sumOf { it.completionTokens },
            compactionCount = compactionRecords.size
        )
    }

    // ── Serializable output types ────────────────────────────────────────────

    @Serializable
    data class ToolStats(
        val count: Int,
        val avgMs: Long,
        val minMs: Long,
        val maxMs: Long,
        val errors: Int
    )

    @Serializable
    data class MetricsSnapshot(
        val totalToolCalls: Int = 0,
        val failedToolCalls: Int = 0,
        val toolStats: Map<String, ToolStats> = emptyMap(),
        val apiCalls: Int = 0,
        val avgApiLatencyMs: Long = 0,
        val totalPromptTokens: Int = 0,
        val totalCompletionTokens: Int = 0,
        val compactionCount: Int = 0
    )
}

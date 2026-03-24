package com.workflow.orchestrator.agent.runtime

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-tool execution metrics tracked during an agent session.
 *
 * Tracks call count, error count, total duration, total tokens consumed,
 * and consecutive error count (for circuit breaker logic).
 */
@Serializable
data class ToolMetric(
    var callCount: Int = 0,
    var errorCount: Int = 0,
    var totalDurationMs: Long = 0,
    var totalTokens: Long = 0,
    var consecutiveErrors: Int = 0
)

/**
 * JSON-serializable snapshot of all session metrics at a point in time.
 */
@Serializable
data class SessionSnapshot(
    val toolCalls: Map<String, ToolMetric>,
    val totalTokens: Long,
    val turnCount: Int,
    val compressionCount: Int,
    val approvalCount: Int,
    val subagentCount: Int,
    val durationMs: Long
)

/**
 * Structured metrics collector for agent sessions.
 *
 * Tracks per-tool metrics (call count, errors, duration, tokens) and session-level
 * counters (turns, compressions, approvals, subagent delegations).
 *
 * Includes a **circuit breaker**: after [CIRCUIT_BREAKER_THRESHOLD] consecutive failures
 * on the same tool, [isCircuitBroken] returns true. The session should inject a warning
 * message telling the LLM to try a different approach.
 *
 * Thread-safe: uses [ConcurrentHashMap] for per-tool metrics. Session-level counters
 * are incremented from the single-threaded ReAct loop.
 */
class AgentMetrics {
    companion object {
        /** Number of consecutive failures before the circuit breaker trips. */
        const val CIRCUIT_BREAKER_THRESHOLD = 5

        private val json = Json { encodeDefaults = true; prettyPrint = false }
    }

    private val tools = ConcurrentHashMap<String, ToolMetric>()
    private val startTime = System.currentTimeMillis()

    /** Number of ReAct loop iterations. */
    var turnCount: Int = 0

    /** Number of context compressions triggered. */
    var compressionCount: Int = 0

    /** Number of approval gates triggered. */
    var approvalCount: Int = 0

    /** Number of subagent delegations. */
    var subagentCount: Int = 0

    /**
     * Record the result of a single tool call.
     *
     * @param name Tool name (e.g., "read_file", "edit_file")
     * @param durationMs How long the tool execution took
     * @param success Whether the tool call succeeded
     * @param tokens Estimated tokens consumed by the tool result
     */
    @Synchronized
    fun recordToolCall(name: String, durationMs: Long, success: Boolean, tokens: Long) {
        tools.getOrPut(name) { ToolMetric() }.apply {
            callCount++
            totalDurationMs += durationMs
            totalTokens += tokens
            if (success) {
                consecutiveErrors = 0
            } else {
                errorCount++
                consecutiveErrors++
            }
        }
    }

    /**
     * Check if a tool's circuit breaker has tripped.
     *
     * @param toolName The tool to check
     * @return true if the tool has failed [CIRCUIT_BREAKER_THRESHOLD] or more consecutive times
     */
    fun isCircuitBroken(toolName: String): Boolean {
        return (tools[toolName]?.consecutiveErrors ?: 0) >= CIRCUIT_BREAKER_THRESHOLD
    }

    /**
     * Get the current consecutive error count for a tool.
     *
     * @param toolName The tool to check
     * @return Number of consecutive failures, or 0 if the tool has not been called
     */
    fun consecutiveErrors(toolName: String): Int {
        return tools[toolName]?.consecutiveErrors ?: 0
    }

    /**
     * Get a snapshot of all metrics at this point in time.
     * The snapshot is a deep frozen copy — further mutations to AgentMetrics will not affect it.
     */
    fun snapshot(): SessionSnapshot = SessionSnapshot(
        toolCalls = tools.mapValues { (_, m) -> m.copy() },
        totalTokens = tools.values.sumOf { it.totalTokens },
        turnCount = turnCount,
        compressionCount = compressionCount,
        approvalCount = approvalCount,
        subagentCount = subagentCount,
        durationMs = System.currentTimeMillis() - startTime
    )

    /**
     * Serialize the current metrics snapshot to JSON.
     */
    fun toJson(): String = json.encodeToString(snapshot())
}

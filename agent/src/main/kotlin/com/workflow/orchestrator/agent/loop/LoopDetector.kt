package com.workflow.orchestrator.agent.loop

/**
 * Detects when the LLM calls the same tool with identical arguments
 * repeatedly, which wastes tokens without making progress.
 *
 * Faithful port of Cline's loop-detection.ts:
 * - Soft threshold (3): inject a warning, giving the LLM one chance to self-correct
 * - Hard threshold (5): escalate to user or fail task
 * - Signature computation: filter out metadata params, sort keys alphabetically,
 *   JSON stringify for comparison
 *
 * @see <a href="https://github.com/cline/cline/blob/main/src/core/task/loop-detection.ts">Cline source</a>
 */
class LoopDetector(
    private val softThreshold: Int = SOFT_THRESHOLD_DEFAULT,
    private val hardThreshold: Int = HARD_THRESHOLD_DEFAULT
) {
    private var lastToolName: String? = null
    private var lastSignature: String? = null
    private var consecutiveCount: Int = 0

    /**
     * Record a tool call and check for loops.
     *
     * Must be called BEFORE processing the tool result, matching Cline's
     * "call before updating lastToolName/lastToolParams" pattern.
     *
     * @param toolName the name of the tool being called
     * @param arguments the raw JSON arguments string from the tool call
     * @return the current loop status
     */
    fun recordToolCall(toolName: String, arguments: String): LoopStatus {
        val signature = toolCallSignature(arguments)

        if (toolName == lastToolName && signature == lastSignature) {
            consecutiveCount++
        } else {
            consecutiveCount = 1
        }

        lastToolName = toolName
        lastSignature = signature

        return when {
            consecutiveCount >= hardThreshold -> LoopStatus.HARD_LIMIT
            consecutiveCount >= softThreshold -> LoopStatus.SOFT_WARNING
            else -> LoopStatus.OK
        }
    }

    /**
     * Reset loop detection state. Called when the task changes or a
     * fundamentally different action is taken.
     */
    fun reset() {
        lastToolName = null
        lastSignature = null
        consecutiveCount = 0
    }

    /** Current consecutive identical call count. Exposed for testing/observability. */
    val currentCount: Int get() = consecutiveCount

    companion object {
        const val SOFT_THRESHOLD_DEFAULT = 3
        const val HARD_THRESHOLD_DEFAULT = 5

        /**
         * Params that are metadata/tracking, not tool-relevant input.
         * These change between calls even when the user-facing arguments are identical.
         * Matches Cline's IGNORED_PARAMS set.
         */
        private val IGNORED_PARAMS = emptySet<String>()

        /**
         * Compute a canonical signature for a tool call's params.
         * Strips metadata fields and sorts keys via alphabetical ordering
         * so key order doesn't affect comparison.
         *
         * Port of Cline's toolCallSignature() — the params are always flat
         * string-valued JSON, so sorting the keys and re-serializing suffices.
         */
        fun toolCallSignature(arguments: String): String {
            if (arguments.isBlank()) return "{}"
            return try {
                val parsed = kotlinx.serialization.json.Json.parseToJsonElement(arguments)
                if (parsed is kotlinx.serialization.json.JsonObject) {
                    val filtered = parsed.entries
                        .filter { it.key !in IGNORED_PARAMS }
                        .sortedBy { it.key }
                    if (filtered.isEmpty()) return "{}"
                    buildString {
                        append("{")
                        filtered.forEachIndexed { index, (key, value) ->
                            if (index > 0) append(",")
                            append("\"$key\":$value")
                        }
                        append("}")
                    }
                } else {
                    arguments // not an object, return as-is
                }
            } catch (_: Exception) {
                arguments // unparseable, use raw string
            }
        }
    }
}

enum class LoopStatus {
    /** No repeated tool calls detected. */
    OK,
    /** Soft threshold crossed (3 consecutive identical calls). Inject warning. */
    SOFT_WARNING,
    /** Hard threshold crossed (5 consecutive identical calls). Escalate/fail. */
    HARD_LIMIT
}

package com.workflow.orchestrator.agent.tools.integration.sonar

import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.coroutines.delay

/**
 * Retry helper for transient SonarQube API failures during local_analysis
 * post-scan polling. Uses exponential backoff (delay doubles after each
 * failure). Returns the last result — successful or failed — so the caller
 * can propagate the original error message.
 */
object SonarRetry {

    /**
     * Run [block] up to [maxAttempts] times. Backs off [initialDelayMs] →
     * 2× → 4× between retries. Stops early when [block] returns a non-error
     * result, or when [shouldRetry] returns false for the latest error.
     */
    suspend fun withBackoff(
        maxAttempts: Int,
        initialDelayMs: Long,
        shouldRetry: (ToolResult) -> Boolean = { true },
        block: suspend () -> ToolResult
    ): ToolResult {
        require(maxAttempts >= 1) { "maxAttempts must be >= 1" }
        var delayMs = initialDelayMs
        var last: ToolResult = block()
        var attempts = 1
        while (last.isError && attempts < maxAttempts && shouldRetry(last)) {
            delay(delayMs)
            delayMs *= 2
            last = block()
            attempts++
        }
        return last
    }
}

package com.workflow.orchestrator.agent.tools.integration.sonar

/**
 * Extracts a SonarQube Compute Engine task ID from a single line of scanner
 * stdout. Stricter than the prior substring-based approach: the task-ID
 * character class is bounded to alphanumerics, dash, and underscore so an
 * extra query-string suffix (`&format=json`) or trailing whitespace can't
 * leak into the captured value.
 */
object CeTaskIdParser {

    private val PATTERN = Regex("api/ce/task\\?id=([A-Za-z0-9_-]+)")

    /** Returns the first task ID in [line], or null if none. */
    fun extract(line: String): String? {
        if (line.isBlank()) return null
        return PATTERN.find(line)?.groupValues?.get(1)
    }
}

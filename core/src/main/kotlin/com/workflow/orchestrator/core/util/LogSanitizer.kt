package com.workflow.orchestrator.core.util

/**
 * Sanitizes user-provided input before including it in log statements.
 *
 * Prevents log injection attacks where an attacker could craft input containing
 * newline characters to forge log entries or corrupt log analysis tools.
 *
 * Usage: `log.info("Searching for query=${LogSanitizer.sanitize(userQuery)}")`
 */
object LogSanitizer {

    /**
     * Replaces control characters (newline, carriage return, tab) with their
     * escaped representations, preventing log injection via multi-line input.
     */
    fun sanitize(input: String): String =
        input.replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
}

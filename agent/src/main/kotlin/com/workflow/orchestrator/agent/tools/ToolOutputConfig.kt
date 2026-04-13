package com.workflow.orchestrator.agent.tools

import com.intellij.openapi.diagnostic.Logger

/**
 * Per-tool output configuration and filtering utilities.
 *
 * Inspired by Claude Code's tool output management: tools can declare their
 * own output limits, and the LLM can request filtered output via grep_pattern,
 * head/tail, or save-to-file.
 */
data class ToolOutputConfig(
    val maxChars: Int = DEFAULT_MAX_CHARS,
) {
    companion object {
        private val LOG = Logger.getInstance(ToolOutputConfig::class.java)

        const val DEFAULT_MAX_CHARS = 50_000
        const val COMMAND_MAX_CHARS = 30_000
        const val SPILL_THRESHOLD_CHARS = 30_000

        val DEFAULT = ToolOutputConfig()
        val COMMAND = ToolOutputConfig(maxChars = COMMAND_MAX_CHARS)

        /**
         * Filter content by regex pattern, keeping only matching lines.
         * Returns all lines if the pattern is invalid (logs warning).
         */
        fun applyGrep(content: String, pattern: String): String {
            val regex = try {
                Regex(pattern)
            } catch (e: Exception) {
                LOG.warn("Invalid grep pattern '$pattern': ${e.message}")
                return content
            }
            return content.lineSequence()
                .filter { regex.containsMatchIn(it) }
                .joinToString("\n")
        }

        /**
         * Keep first [head] and last [tail] lines, replacing middle with omission notice.
         */
        fun applyHeadTail(content: String, head: Int, tail: Int): String {
            val lines = content.lines()
            if (lines.size <= head + tail) return content
            val omitted = lines.size - head - tail
            return (lines.take(head) +
                listOf("[... $omitted lines omitted ...]") +
                lines.takeLast(tail)).joinToString("\n")
        }
    }
}

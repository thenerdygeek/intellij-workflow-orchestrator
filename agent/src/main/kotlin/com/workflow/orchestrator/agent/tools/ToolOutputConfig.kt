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

        /**
         * Standard cap (50K chars) for most tools. Content beyond this limit is
         * middle-truncated by the AgentLoop safety net after any disk-spill attempt.
         * The majority of tools use this default via [DEFAULT].
         */
        const val DEFAULT_MAX_CHARS = 50_000

        /**
         * Extended cap (100K chars) for high-volume tools where the LLM benefits from
         * more in-context visibility before content is spilled to disk. Used by
         * [RunCommandTool] and any tool that overrides [outputConfig] with [COMMAND] —
         * notably `run_tests`, `run_with_coverage`, and `run_inspections` (full-project
         * scope) where large output sets (500-failure JUnit runs, 100+ class coverage
         * reports) still fit within a 100K window and allow the LLM to reason over the
         * full result inline before resorting to a disk-spill read-back.
         *
         * Note: tools using [COMMAND] also participate in disk spilling via
         * [ToolOutputSpiller] at the [SPILL_THRESHOLD_CHARS] threshold (30K), so this
         * cap is a secondary ceiling applied *after* the spiller has already written to
         * disk. It was previously 30K (a Cline carry-over), which double-gated output
         * for tools that already spill.
         */
        const val COMMAND_MAX_CHARS = 100_000

        const val SPILL_THRESHOLD_CHARS = 30_000

        /** Standard config for most tools — 50K char cap. */
        val DEFAULT = ToolOutputConfig()

        /** Extended config for high-volume tools (`run_command`, `run_tests`, etc.) — 100K char cap. */
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

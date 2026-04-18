package com.workflow.orchestrator.agent.tools.process

import com.intellij.openapi.diagnostic.Logger
import java.io.File

/**
 * Processed command output ready for LLM consumption.
 */
data class ProcessedOutput(
    val content: String,
    val wasTruncated: Boolean,
    val totalLines: Int,
    val totalChars: Int,
    val spillPath: String? = null,
)

/**
 * Processes raw command output for LLM consumption with:
 * 1. ANSI escape code stripping
 * 2. Unicode sanitization (zero-width chars, RTL overrides, BOM, format controls)
 * 3. Line-based 50/50 head/tail truncation (keep first half + last half, drop middle)
 * 4. Disk spill for large outputs (write full output to temp file)
 *
 * Replaces the char-based 60/40 truncation in [com.workflow.orchestrator.agent.tools.truncateOutput]
 * with industry-consensus 50/50 LINE-based truncation (Codex CLI / Cline pattern).
 */
object OutputCollector {

    private val log = Logger.getInstance(OutputCollector::class.java)

    /** Matches ANSI escape sequences: ESC [ (params) letter */
    private val ANSI_REGEX = Regex("\u001B\\[[;\\d]*[A-Za-z]")

    /**
     * Matches unsafe Unicode for LLM consumption:
     * - \u200B-\u200D: zero-width space / non-joiner / joiner
     * - \u202A-\u202E: LTR/RTL embedding / override / pop
     * - \uFEFF: BOM (byte-order mark)
     * - \p{Cf}: all Unicode format control characters
     */
    private val UNSAFE_UNICODE_REGEX = Regex("[\\u200B-\\u200D\\u202A-\\u202E\\uFEFF\\p{Cf}]")

    /**
     * Main entry point — process raw output through all stages.
     *
     * Pipeline: empty check → stripAnsi → sanitizeForLLM → spill if >maxMemoryChars
     *          → truncateByLines if >maxResultChars → append size info
     *
     * @param rawOutput       Raw command stdout/stderr
     * @param maxResultChars  Max chars to return in the ToolResult (default 30K)
     * @param maxMemoryChars  Threshold above which full output is spilled to disk (default 1M)
     * @param spillDir        Directory for spill files; null disables spilling
     * @param toolCallId      Identifier for the tool call, used in spill file naming
     */
    fun processOutput(
        rawOutput: String,
        maxResultChars: Int = 30_000,
        maxMemoryChars: Int = 1_000_000,
        spillDir: File? = null,
        toolCallId: String? = null,
    ): ProcessedOutput {
        if (rawOutput.isBlank()) {
            return ProcessedOutput(
                content = "(No output)",
                wasTruncated = false,
                totalLines = 0,
                totalChars = 0,
            )
        }

        // Stage 1: Strip ANSI escape codes
        val stripped = stripAnsi(rawOutput)

        // Stage 2: Sanitize unsafe Unicode
        val sanitized = sanitizeForLLM(stripped)

        val totalChars = sanitized.length
        val totalLines = sanitized.lines().size

        // Stage 3: Spill to disk if over memory threshold
        var spillPath: String? = null
        if (spillDir != null && toolCallId != null && totalChars > maxMemoryChars) {
            spillPath = spillToFile(sanitized, spillDir, toolCallId)
        }

        // Stage 4: Truncate for LLM context if needed
        if (totalChars <= maxResultChars) {
            return ProcessedOutput(
                content = sanitized,
                wasTruncated = false,
                totalLines = totalLines,
                totalChars = totalChars,
                spillPath = spillPath,
            )
        }

        // Estimate max lines from char budget: assume average line length
        val avgLineLen = (totalChars / totalLines).coerceAtLeast(1)
        val maxLines = (maxResultChars / avgLineLen).coerceAtLeast(2)

        val truncated = truncateByLines(sanitized, maxLines)

        // Append size info footer
        val footer = if (spillPath != null) {
            "\n[Total output: $totalChars chars, $totalLines lines. Full output: $spillPath]"
        } else {
            "\n[Total output: $totalChars chars. Use a more targeted command to see specific sections.]"
        }

        return ProcessedOutput(
            content = truncated + footer,
            wasTruncated = true,
            totalLines = totalLines,
            totalChars = totalChars,
            spillPath = spillPath,
        )
    }

    /**
     * Simple entry point for result construction — strips ANSI + sanitizes + truncates.
     * Does not spill to disk.
     */
    fun buildContent(rawOutput: String, maxResultChars: Int): ProcessedOutput {
        return processOutput(
            rawOutput = rawOutput,
            maxResultChars = maxResultChars,
            spillDir = null,
            toolCallId = null,
        )
    }

    /**
     * 50/50 line-based truncation.
     *
     * If content has more lines than [maxLines], keeps the first half and last half,
     * replacing the middle with an omission notice.
     */
    fun truncateByLines(content: String, maxLines: Int): String {
        val lines = content.lines()
        if (lines.size <= maxLines) return content

        val headCount = maxLines / 2
        val tailCount = maxLines - headCount
        val omitted = lines.size - headCount - tailCount

        return (lines.take(headCount) +
            listOf("[... $omitted lines omitted ...]") +
            lines.takeLast(tailCount)).joinToString("\n")
    }

    /**
     * Strip ANSI escape codes (colors, cursor movement, formatting).
     */
    fun stripAnsi(text: String): String {
        return ANSI_REGEX.replace(text, "")
    }

    /**
     * Remove unsafe Unicode characters that could confuse LLMs or enable prompt injection:
     * - Zero-width characters (\u200B, \u200C, \u200D)
     * - RTL/LTR override characters (\u202A-\u202E)
     * - BOM (\uFEFF)
     * - All Unicode format control characters (\p{Cf})
     *
     * Preserves normal Unicode (Greek, CJK, emoji, etc.).
     */
    fun sanitizeForLLM(text: String): String {
        return UNSAFE_UNICODE_REGEX.replace(text, "")
    }

    /**
     * Tail-biased variant of [processOutput] for `run_command`. Keeps the last lines of
     * output (exit summary, last error, stack trace) instead of dropping the middle.
     * All other stages (ANSI strip, Unicode sanitize, disk spill) are identical to [processOutput].
     *
     * Rationale: for shell commands — `mvn test`, `gradle test`, `npm run build`, server
     * startup — the failure summary is always at the end. Middle-drop preserves neither the
     * beginning nor the end reliably; keeping the tail preserves what the LLM needs most.
     */
    fun processOutputTailBiased(
        rawOutput: String,
        maxResultChars: Int = 100_000,
        maxMemoryChars: Int = 1_000_000,
        spillDir: File? = null,
        toolCallId: String? = null,
    ): ProcessedOutput {
        if (rawOutput.isBlank()) {
            return ProcessedOutput(
                content = "(No output)",
                wasTruncated = false,
                totalLines = 0,
                totalChars = 0,
            )
        }

        val stripped = stripAnsi(rawOutput)
        val sanitized = sanitizeForLLM(stripped)

        val totalChars = sanitized.length
        val totalLines = sanitized.lines().size

        var spillPath: String? = null
        if (spillDir != null && toolCallId != null && totalChars > maxMemoryChars) {
            spillPath = spillToFile(sanitized, spillDir, toolCallId)
        }

        if (totalChars <= maxResultChars) {
            return ProcessedOutput(
                content = sanitized,
                wasTruncated = false,
                totalLines = totalLines,
                totalChars = totalChars,
                spillPath = spillPath,
            )
        }

        val truncated = truncateToTail(sanitized, maxResultChars)
        val footer = if (spillPath != null) {
            "\n[Total output: $totalChars chars, $totalLines lines. Full output: $spillPath]"
        } else {
            "\n[Total output: $totalChars chars. Use output_file=true or a more targeted command to see the head.]"
        }

        return ProcessedOutput(
            content = truncated + footer,
            wasTruncated = true,
            totalLines = totalLines,
            totalChars = totalChars,
            spillPath = spillPath,
        )
    }

    /**
     * Keep the last lines that fit in [maxChars], prepend a head-omission marker.
     * Line-based — never splits a line mid-character. The returned content (excluding
     * any caller-appended footer) is ≤ [maxChars] plus the marker line.
     *
     * If no complete lines fit (a single line is longer than [maxChars]), the marker
     * alone is returned so the caller always gets a non-empty, structurally valid result.
     */
    internal fun truncateToTail(content: String, maxChars: Int): String {
        val lines = content.lines()
        val kept = ArrayDeque<String>()
        var used = 0
        for (i in lines.indices.reversed()) {
            val lineCost = lines[i].length + 1  // +1 for the joining \n
            if (used + lineCost > maxChars) break
            kept.addFirst(lines[i])
            used += lineCost
        }
        val omittedCount = lines.size - kept.size
        if (omittedCount == 0) return content
        return if (kept.isEmpty()) {
            "[... $omittedCount lines omitted from head ...]"
        } else {
            "[... $omittedCount lines omitted from head ...]\n" + kept.joinToString("\n")
        }
    }

    /**
     * Write full output to a temp file for later retrieval.
     *
     * Creates the file in [spillDir] named `run-cmd-{toolCallId}-{epoch}.txt`.
     * Calls [File.deleteOnExit] for cleanup.
     *
     * @return Absolute path to the spill file, or null on failure.
     */
    fun spillToFile(content: String, spillDir: File, toolCallId: String): String? {
        return try {
            spillDir.mkdirs()
            val fileName = "run-cmd-$toolCallId-${System.currentTimeMillis() / 1000}.txt"
            val file = File(spillDir, fileName)
            file.writeText(content)
            file.deleteOnExit()
            file.absolutePath
        } catch (e: Exception) {
            log.warn("Failed to spill command output to $spillDir: ${e.message}")
            null
        }
    }
}

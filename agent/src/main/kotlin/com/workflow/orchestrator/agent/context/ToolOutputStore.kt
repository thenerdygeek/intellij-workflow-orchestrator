package com.workflow.orchestrator.agent.context

import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Saves full tool output to disk for re-reads after context pruning.
 *
 * When a tool returns a large result (file content, search results, command output),
 * the full content is saved to disk. If the result gets pruned from context later,
 * the agent can re-read specific sections via read_file with offset/limit.
 *
 * Storage: {sessionDir}/tool-outputs/{toolCallId}.txt
 *
 * Follows OpenCode's pattern: full content on disk, summary in context after pruning.
 */
class ToolOutputStore(private val sessionDir: File?) {

    companion object {
        private val LOG = Logger.getInstance(ToolOutputStore::class.java)
        const val MAX_LINES = 2000
        const val MAX_CHARS = 50 * 1024  // ~50K characters

        /**
         * Middle-truncate content, keeping the first [headRatio] and last (1-headRatio) portions.
         * This preserves error messages, exit codes, and summaries that appear at the end of output.
         */
        fun middleTruncate(content: String, maxChars: Int, headRatio: Double = 0.6): String {
            if (content.length <= maxChars) return content
            val headChars = (maxChars * headRatio).toInt()
            val tailChars = maxChars - headChars - 200 // reserve 200 for the marker
            val omitted = content.length - headChars - tailChars
            return content.take(headChars) +
                "\n\n[... $omitted characters omitted from middle. Showing first $headChars + last $tailChars chars ...]\n\n" +
                content.takeLast(tailChars)
        }
    }

    private val outputDir: File? get() = sessionDir?.let { File(it, "tool-outputs").also { d -> d.mkdirs() } }
    private val storedPaths = ConcurrentHashMap<String, String>()

    /**
     * Save tool output to disk. Returns the disk path.
     * Content is capped at MAX_CHARS characters on disk.
     */
    fun save(toolCallId: String, content: String): String? {
        val dir = outputDir ?: return null
        return try {
            val file = File(dir, "$toolCallId.txt")
            file.writeText(content.take(MAX_CHARS))
            val path = file.absolutePath
            storedPaths[toolCallId] = path
            path
        } catch (e: Exception) {
            LOG.debug("ToolOutputStore: failed to save $toolCallId: ${e.message}")
            null
        }
    }

    /**
     * Get the disk path for a previously saved tool output.
     */
    fun getPath(toolCallId: String): String? = storedPaths[toolCallId]

    /**
     * Cap content to MAX_LINES / MAX_CHARS characters, append truncation hint if needed.
     */
    fun capContent(content: String, diskPath: String?): String {
        val lines = content.lines()
        val cappedByLines = if (lines.size > MAX_LINES) {
            lines.take(MAX_LINES).joinToString("\n") +
                "\n\n[Truncated at $MAX_LINES lines — ${lines.size} total." +
                (if (diskPath != null) " Full output saved to: $diskPath. Use read_file with offset/limit to view more.]" else "]")
        } else content

        return if (cappedByLines.length > MAX_CHARS) {
            cappedByLines.take(MAX_CHARS) +
                "\n\n[Truncated at ${MAX_CHARS / 1024}K chars." +
                (if (diskPath != null) " Full output at: $diskPath]" else "]")
        } else cappedByLines
    }
}

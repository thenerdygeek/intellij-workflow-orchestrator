package com.workflow.orchestrator.agent.tools

import com.intellij.openapi.diagnostic.Logger
import java.nio.file.Path
import java.time.Instant

/**
 * Spills large tool outputs to disk and returns a preview with file reference.
 * Inspired by Claude Code's persist-to-disk mechanism (>30K chars -> file + preview).
 *
 * Files are written to the session's output directory and can be read back
 * via read_file or search_code.
 */
class ToolOutputSpiller(private val spillDir: Path) {

    private val log = Logger.getInstance(ToolOutputSpiller::class.java)

    data class SpillResult(
        val preview: String,
        val spilledToFile: String?,
    )

    /**
     * If [content] exceeds [threshold] characters, write the full content to a file
     * under [spillDir] and return a preview (first 20 + last 10 lines) with the file path.
     * Otherwise return the content unchanged.
     */
    fun spill(
        toolName: String,
        content: String,
        threshold: Int = ToolOutputConfig.SPILL_THRESHOLD_CHARS,
    ): SpillResult {
        if (content.length <= threshold) {
            return SpillResult(preview = content, spilledToFile = null)
        }

        val fileName = "${toolName}-${Instant.now().epochSecond}-output.txt"
        val file = spillDir.resolve(fileName).toFile()
        try {
            spillDir.toFile().mkdirs()
            file.writeText(content)
        } catch (e: Exception) {
            log.warn("Failed to spill output to ${file.absolutePath}: ${e.message}")
            // Fallback: return truncated content without file reference
            return SpillResult(
                preview = truncateOutput(content, threshold),
                spilledToFile = null,
            )
        }

        val lines = content.lines()
        val headLines = lines.take(20).joinToString("\n")
        val tailLines = if (lines.size > 30) "\n...\n" + lines.takeLast(10).joinToString("\n") else ""
        val preview = """$headLines$tailLines

[Output saved to: ${file.absolutePath} (${content.length} chars, ${lines.size} lines)]
[Use read_file or search_code to explore the full output]"""

        return SpillResult(preview = preview, spilledToFile = file.absolutePath)
    }
}

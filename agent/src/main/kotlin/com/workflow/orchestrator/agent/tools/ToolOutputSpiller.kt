package com.workflow.orchestrator.agent.tools

import com.intellij.openapi.diagnostic.Logger
import com.workflow.orchestrator.agent.security.CredentialRedactor
import com.workflow.orchestrator.agent.session.AtomicFileWriter
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

    // B6: epoch-second filenames collide when the same tool spills twice within one second —
    // the second write silently overwrote the first, leaving the earlier result's spillPath
    // pointing at the later content. A monotonic counter disambiguates the name.
    private val spillCounter = java.util.concurrent.atomic.AtomicLong(0)

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
        rawContent: String,
        threshold: Int = ToolOutputConfig.SPILL_THRESHOLD_CHARS,
    ): SpillResult {
        // Redact credentials before anything touches disk or the preview — a tool that
        // prints an Authorization header (e.g. `curl -v`) must not leak the raw value
        // into {sessionDir}/tool-output/ (queued incidental agent-runtime:F-19).
        val content = CredentialRedactor.redact(rawContent)
        if (content.length <= threshold) {
            return SpillResult(preview = content, spilledToFile = null)
        }

        val fileName = "${toolName}-${Instant.now().epochSecond}-${spillCounter.incrementAndGet()}-output.txt"
        val file = spillDir.resolve(fileName).toFile()
        try {
            spillDir.toFile().mkdirs()
            file.writeText(content)
            // rw------- on the spill file (E2 policy consistency — P4 Q2). Tool output can
            // carry redacted-but-still-sensitive context; keep it owner-readable only.
            AtomicFileWriter.applyOwnerOnlyPerms(file.toPath())
        } catch (e: Exception) {
            log.warn("Failed to spill output to ${file.absolutePath}: ${e.message}")
            // Fallback: return truncated content without file reference
            return SpillResult(
                preview = truncateOutput(content, threshold),
                spilledToFile = null,
            )
        }

        // P2-4: single lineSequence() pass instead of content.lines() — a 1MB output would
        // otherwise materialize thousands of String objects just for head-20 + tail-10 + count.
        val head = ArrayList<String>(HEAD_LINES)
        val tailRing = ArrayDeque<String>(TAIL_LINES)
        var totalLines = 0
        for (line in content.lineSequence()) {
            totalLines++
            if (head.size < HEAD_LINES) head.add(line)
            tailRing.addLast(line)
            if (tailRing.size > TAIL_LINES) tailRing.removeFirst()
        }
        val headLines = head.joinToString("\n")
        val tailLines = if (totalLines > HEAD_LINES + TAIL_LINES) {
            "\n...\n" + tailRing.joinToString("\n")
        } else {
            ""
        }
        val preview = """$headLines$tailLines

[Output saved to: ${file.absolutePath} (${content.length} chars, $totalLines lines)]
[Use read_file or search_code to explore the full output]"""

        return SpillResult(preview = preview, spilledToFile = file.absolutePath)
    }

    private companion object {
        const val HEAD_LINES = 20
        const val TAIL_LINES = 10
    }
}

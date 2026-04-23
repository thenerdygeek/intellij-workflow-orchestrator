package com.workflow.orchestrator.agent.memory

import java.nio.file.Files
import java.nio.file.Path

/**
 * Loads the always-injected memory index (`MEMORY.md`) for a session.
 *
 * Matches the Claude Code auto-memory pattern: a single index file per project that
 * enumerates all individual memory files. The index is loaded once at session start
 * and injected into the system prompt; individual memory files are fetched on demand
 * by the LLM via `read_file`. Writes go through `create_file` / `edit_file`.
 *
 * Truncated past line 200 to bound the always-in-prompt footprint.
 */
object MemoryIndex {

    private const val FILENAME = "MEMORY.md"
    const val MAX_LINES = 200

    /**
     * @return trimmed `MEMORY.md` content, or `null` if the file is missing / memory dir absent.
     */
    fun load(memoryDir: Path): String? {
        val file = memoryDir.resolve(FILENAME)
        if (!Files.isRegularFile(file)) return null

        val raw = try {
            Files.readString(file)
        } catch (_: Exception) {
            return null
        }

        val lines = raw.lines()
        return if (lines.size <= MAX_LINES) {
            raw
        } else {
            buildString {
                lines.take(MAX_LINES).joinTo(this, "\n")
                append("\n<!-- MEMORY.md truncated at $MAX_LINES lines (file has ${lines.size}) — keep the index concise -->")
            }
        }
    }
}

package com.workflow.orchestrator.agent.memory

import java.nio.file.Files
import java.nio.file.Path

/**
 * Loads the always-injected memory index (`MEMORY.md`) for a session.
 *
 * Matches the Claude Code auto-memory pattern: a single index file per project that
 * enumerates all individual memory files. The index is loaded into the system prompt;
 * individual memory files are fetched on demand by the LLM via `read_file`. Writes
 * go through `create_file` / `edit_file`.
 *
 * Truncated past line 200 to bound the always-in-prompt footprint. The truncation
 * keeps the **last** N lines (assumed to be the most recently written, since the
 * prompt instructs the LLM to insert new entries at the top of their section under
 * the corresponding `## Project` / `## User` / `## Feedback` / `## Reference` heading
 * — so "most recently written" naturally lands near the top of each section, and the
 * tail of the file is the oldest. Older entries are dropped first as the file grows.
 */
object MemoryIndex {

    private const val FILENAME = "MEMORY.md"
    const val MAX_LINES = 200

    private const val SEED_CONTENT = "# Memory Index\n"

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
                append("<!-- MEMORY.md truncated at $MAX_LINES lines (file has ${lines.size}) — older entries above this line were omitted; insert new entries near the top of their section to keep them in-prompt. -->\n")
                lines.takeLast(MAX_LINES).joinTo(this, "\n")
            }
        }
    }

    /**
     * Writes a stub `MEMORY.md` (`# Memory Index\n`) when none exists.
     *
     * The system prompt only reveals the memory directory's absolute path via the
     * `Contents of <path>:` block, which fires only when `load()` returns non-null.
     * Seeding makes that block fire on the very first session — without this, the
     * LLM has no source-of-truth for the absolute path on its first memory write
     * and a guess like `~/.workflow-orchestrator/memory/...` fails `PathValidator`.
     *
     * Idempotent: no-op when the file already exists. Failures are swallowed so a
     * read-only FS doesn't break session startup.
     */
    fun seedIfMissing(memoryDir: Path) {
        val file = memoryDir.resolve(FILENAME)
        if (Files.exists(file)) return
        try {
            Files.writeString(file, SEED_CONTENT)
        } catch (_: Exception) {
            // Non-fatal: memory just stays unavailable for the session.
        }
    }
}

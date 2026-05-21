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

    // Per-memoryDir serialization: two concurrent writes to the same MEMORY.md cannot
    // interleave. Keyed by the absolute path string so the lock survives across calls
    // for the same project, and so two different projects don't contend with each other.
    // Same pattern shape as SessionStore's per-session mutex (kept non-suspend so callers
    // outside coroutine scope can still drive these hooks).
    private val indexLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()
    private fun lockFor(memoryDir: Path): Any =
        indexLocks.computeIfAbsent(memoryDir.toAbsolutePath().toString()) { Object() }

    /**
     * Auto-syncs MEMORY.md after a memory file is created.
     *
     * Parses YAML frontmatter (`name`, `description`, `type`) from [createdFile] and
     * appends a bullet line under the corresponding `## <Type>` section. Creates the
     * section heading if it doesn't exist. Idempotent — re-running for the same file
     * is a no-op once a line targeting that filename is present.
     *
     * No-op when [createdFile] is MEMORY.md itself (so a future edit_file on the index
     * never recurses into this code).
     *
     * Atomic: writes to `.tmp` then `Files.move(ATOMIC_MOVE, REPLACE_EXISTING)`. Failures
     * leave the existing MEMORY.md untouched. Serialized per [memoryDir] via [lockFor]
     * so concurrent calls (e.g. sub-agents both writing memory) cannot interleave.
     */
    fun onMemoryFileCreated(memoryDir: Path, createdFile: Path) {
        if (createdFile.fileName.toString() == FILENAME) return
        synchronized(lockFor(memoryDir)) {
            seedIfMissing(memoryDir)

            val filename = createdFile.fileName.toString()
            val indexPath = memoryDir.resolve(FILENAME)

            val (name, description, type) = parseFrontmatter(createdFile, filename)

            val current = try { Files.readString(indexPath) } catch (_: Exception) { return }

            // Idempotency: any existing line targeting (<filename>) means we already wrote it.
            if (current.lines().any { it.contains("](${filename})") }) return

            val sectionHeading = "## " + type.replaceFirstChar { it.uppercase() }
            val entryLine = "- [$name]($filename) — $description"

            val updated = appendEntryUnderSection(current, sectionHeading, entryLine)
            atomicWrite(indexPath, updated)
        }
    }

    /**
     * Auto-syncs MEMORY.md after a memory file is deleted. Removes any bullet line
     * targeting [deletedFilename]. No-op if MEMORY.md is absent or no matching line
     * exists. Atomic write — see [onMemoryFileCreated] for the contract. Serialized
     * per [memoryDir] via [lockFor].
     */
    fun onMemoryFileDeleted(memoryDir: Path, deletedFilename: String) {
        synchronized(lockFor(memoryDir)) {
            val indexPath = memoryDir.resolve(FILENAME)
            if (!Files.isRegularFile(indexPath)) return

            val current = try { Files.readString(indexPath) } catch (_: Exception) { return }
            val targetMarker = "]($deletedFilename)"

            val kept = current.lines().filter { line ->
                !(line.trimStart().startsWith("- ") && line.contains(targetMarker))
            }
            if (kept.size == current.lines().size) return  // no-op — nothing matched.

            atomicWrite(indexPath, kept.joinToString("\n"))
        }
    }

    // ── helpers ──

    private data class Frontmatter(val name: String, val description: String, val type: String)

    /** Frontmatter is bounded by `---` lines. We only need three fields; a tiny regex parse beats a YAML dep. */
    private fun parseFrontmatter(file: Path, filename: String): Frontmatter {
        val body = try { Files.readString(file) } catch (_: Exception) { "" }
        val lines = body.lines()
        val fmStart = lines.indexOfFirst { it.trim() == "---" }
        val fmEnd = if (fmStart >= 0) {
            lines.drop(fmStart + 1).indexOfFirst { it.trim() == "---" }.let { if (it >= 0) it + fmStart + 1 else -1 }
        } else -1

        val fields = mutableMapOf<String, String>()
        if (fmStart >= 0 && fmEnd > fmStart) {
            val keyValueRe = Regex("""^(name|description|type)\s*:\s*(.+?)\s*$""")
            for (i in (fmStart + 1) until fmEnd) {
                keyValueRe.matchEntire(lines[i])?.let { m ->
                    fields[m.groupValues[1]] = m.groupValues[2]
                }
            }
        }

        val slug = filename.removeSuffix(".md")
        val name = fields["name"]?.takeIf { it.isNotBlank() } ?: slug
        val description = fields["description"] ?: ""

        val typeFromFm = fields["type"]?.lowercase()
        val type = when (typeFromFm) {
            "user", "feedback", "project", "reference" -> typeFromFm
            else -> inferTypeFromPrefix(slug)
        }
        return Frontmatter(name, description, type)
    }

    private fun inferTypeFromPrefix(slug: String): String {
        val prefix = slug.substringBefore('_', missingDelimiterValue = "").lowercase()
        return when (prefix) {
            "user", "feedback", "project", "reference" -> prefix
            else -> "reference"
        }
    }

    /**
     * Appends [entryLine] under [sectionHeading]. If the heading is absent, adds it
     * at the end of the file (preceded by a blank line) before the entry.
     *
     * Insertion point within the section: right after the LAST consecutive bullet
     * directly beneath the heading. Heading and existing bullets are preserved verbatim.
     */
    private fun appendEntryUnderSection(current: String, sectionHeading: String, entryLine: String): String {
        val lines = current.lines().toMutableList()
        val headingIdx = lines.indexOfFirst { it.trim() == sectionHeading }
        if (headingIdx < 0) {
            // Section missing — append blank-line + heading + entry at end of file.
            val trailingBlank = lines.isNotEmpty() && lines.last().isBlank()
            if (!trailingBlank) lines.add("")
            lines.add(sectionHeading)
            lines.add(entryLine)
            return lines.joinToString("\n")
        }

        // Walk forward from the heading; insertion point = first line that's NOT a bullet,
        // or end-of-file.
        var insertAt = headingIdx + 1
        while (insertAt < lines.size && (lines[insertAt].isBlank() || lines[insertAt].trimStart().startsWith("- "))) {
            // Stop walking past a blank line that's followed by a non-bullet (we're out of the section).
            if (lines[insertAt].isBlank() && (insertAt + 1 >= lines.size || !lines[insertAt + 1].trimStart().startsWith("- "))) {
                break
            }
            insertAt++
        }
        lines.add(insertAt, entryLine)
        return lines.joinToString("\n")
    }

    private fun atomicWrite(target: Path, content: String) {
        val tmp = target.resolveSibling(target.fileName.toString() + ".tmp")
        try {
            Files.writeString(tmp, content)
            Files.move(tmp, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } catch (_: Exception) {
            try { Files.deleteIfExists(tmp) } catch (_: Exception) {}
        }
    }
}

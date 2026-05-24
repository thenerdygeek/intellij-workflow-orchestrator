package com.workflow.orchestrator.agent.research

import java.nio.file.Files
import java.nio.file.Path

/**
 * Auto-injected index for the research sub-agent's dump files. Lives at
 * `{agentDir}/research/RESEARCH.md` and is mirrored by
 * [com.workflow.orchestrator.agent.memory.MemoryIndex] for the project-memory
 * equivalent at `{agentDir}/memory/MEMORY.md`.
 *
 * Index entries are appended by [onResearchFileCreated] (added in T4), fired by
 * `CreateFileTool` when the persona writes a new dump file under the research dir.
 * The research persona itself never writes to `RESEARCH.md` — eliminates the
 * parallel-research-agent race surface.
 *
 * The orchestrator's [com.workflow.orchestrator.agent.prompt.SystemPrompt] auto-injects
 * the truncated index (max 200 lines) into the system prompt so subsequent sessions
 * see which prior research artifacts exist and can `read_file` any of them on demand.
 */
object ResearchIndex {

    internal const val FILENAME = "RESEARCH.md"
    private const val MAX_LINES = 200

    /**
     * Reads `{researchDir}/RESEARCH.md`, truncating to [MAX_LINES] lines if larger.
     * Returns null if the dir doesn't exist, the file doesn't exist, or the file
     * is empty/blank. Truncation appends a single marker line so the LLM can tell
     * the index was clipped.
     */
    fun load(researchDir: Path): String? {
        if (!Files.isDirectory(researchDir)) return null
        val indexFile = researchDir.resolve(FILENAME)
        if (!Files.isRegularFile(indexFile)) return null
        val content = try {
            Files.readString(indexFile)
        } catch (_: Exception) {
            return null
        }
        if (content.isBlank()) return null
        val lines = content.lines()
        if (lines.size <= MAX_LINES) return content
        val truncated = lines.take(MAX_LINES).toMutableList()
        truncated.add("… (truncated at $MAX_LINES lines; ${lines.size - MAX_LINES} more entries in RESEARCH.md)")
        return truncated.joinToString("\n")
    }

    private const val SECTION_HEADING = "## Entries"
    private val indexLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()

    /**
     * Idempotent append: derives title + one-line hook from [createdFile]'s frontmatter
     * + first finding paragraph, then appends a single bullet under the `## Entries`
     * section of `RESEARCH.md`. If the bullet's filename already appears in the index,
     * the call is a no-op.
     *
     * Self-edit guard: when [createdFile.fileName] is `RESEARCH.md` itself, the call
     * returns immediately — prevents recursion when this hook is fired by the
     * `CreateFileTool` path that creates the index file as a side effect.
     *
     * Race-safe: per-research-dir [java.lang.Object] lock + atomic rewrite (write to
     * `.tmp` then `Files.move(ATOMIC_MOVE, REPLACE_EXISTING)`).
     *
     * Fired by `CreateFileTool.tryResearchIndexHook` after every successful create
     * under the research dir; see T5 wiring.
     */
    fun onResearchFileCreated(researchDir: Path, createdFile: Path) {
        if (createdFile.fileName.toString() == FILENAME) return  // self-edit guard

        val lock = indexLocks.computeIfAbsent(researchDir.toAbsolutePath().toString()) { Object() }
        synchronized(lock) {
            try {
                val (title, hook) = extractTitleAndHook(createdFile) ?: return
                val filename = createdFile.fileName.toString()
                val entryLine = "- [$title]($filename) — $hook"

                val indexFile = researchDir.resolve(FILENAME)
                val current = if (Files.isRegularFile(indexFile)) Files.readString(indexFile) else "# Research Index\n"

                // Idempotence: skip if this filename already appears.
                if (current.contains("]($filename)")) return

                val updated = appendEntryUnderSection(current, SECTION_HEADING, entryLine)
                atomicWrite(indexFile, updated)
            } catch (_: Throwable) {
                // Mirror MemoryIndex's silent-swallow convention.
            }
        }
    }

    /**
     * Returns `(title, hook)` parsed from the dump file:
     * - title = the first `# ` heading after the frontmatter
     * - hook  = the first non-empty line of the `## Findings` section, truncated to 80 chars (+ "…" suffix if truncated)
     *
     * Returns null if either cannot be located.
     */
    private fun extractTitleAndHook(file: Path): Pair<String, String>? {
        val text = try { Files.readString(file) } catch (_: Throwable) { return null }
        val lines = text.lines()

        // Skip YAML frontmatter (--- ... ---).
        var i = 0
        if (i < lines.size && lines[i].trim() == "---") {
            i++
            while (i < lines.size && lines[i].trim() != "---") i++
            if (i < lines.size) i++  // skip closing ---
        }

        // Title: next `# ` line (NOT `## `).
        var title: String? = null
        while (i < lines.size) {
            val l = lines[i].trim()
            if (l.startsWith("# ") && !l.startsWith("## ")) {
                title = l.removePrefix("# ").trim()
                break
            }
            i++
        }
        if (title.isNullOrBlank()) return null

        // Hook: first non-empty, non-heading line under `## Findings`.
        val findingsIdx = lines.indexOfFirst { it.trim() == "## Findings" }
        if (findingsIdx < 0) return null
        val hook = lines.drop(findingsIdx + 1)
            .firstOrNull { it.isNotBlank() && !it.startsWith("##") }
            ?.trim()
            ?.let { if (it.length > 80) it.take(80) + "…" else it }
            ?: return null

        return title to hook
    }

    private fun appendEntryUnderSection(current: String, sectionHeading: String, entryLine: String): String {
        val lines = current.lines().toMutableList()
        val headingIdx = lines.indexOfFirst { it.trim() == sectionHeading }
        if (headingIdx < 0) {
            // Section missing — append blank line + heading + entry at end of file.
            if (lines.isNotEmpty() && lines.last().isNotBlank()) lines.add("")
            lines.add(sectionHeading)
            lines.add(entryLine)
            return lines.joinToString("\n")
        }

        // Walk forward from the heading; insertion point = last consecutive bullet OR end-of-file.
        var insertAt = headingIdx + 1
        while (insertAt < lines.size && (lines[insertAt].isBlank() || lines[insertAt].trimStart().startsWith("- "))) {
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
        } catch (_: Throwable) {
            try { Files.deleteIfExists(tmp) } catch (_: Throwable) {}
        }
    }
}

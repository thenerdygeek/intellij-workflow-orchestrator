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
}

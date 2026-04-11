package com.workflow.orchestrator.agent.memory.auto

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant

/**
 * Audit log of every extraction applied by AutoMemoryManager.
 *
 * Purpose (per senior reviewer recommendation): "Non-negotiable for the first
 * three months of production use; you will find bugs this way that no amount
 * of prompt review will catch." The developer uses this to sanity-check what
 * the cheap model (Haiku) is actually saving vs. what it should be.
 *
 * Storage: JSONL at ~/.workflow-orchestrator/{proj}/agent/extraction-log.jsonl
 * Cap: 500 entries, oldest trimmed on overflow.
 */
class ExtractionLog(private val logFile: File) {

    companion object {
        private const val MAX_ENTRIES = 500
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun forProject(agentDir: File): ExtractionLog {
            return ExtractionLog(File(agentDir, "extraction-log.jsonl"))
        }
    }

    @Serializable
    data class Entry(
        val timestamp: Long,
        val sessionId: String,
        val source: String,
        val coreUpdates: List<CoreMemoryUpdate>,
        val archivalInserts: List<ArchivalInsert>
    )

    /** Append a new entry, trimming oldest if over cap. */
    @Synchronized
    fun record(
        sessionId: String,
        source: String,
        coreUpdates: List<CoreMemoryUpdate>,
        archivalInserts: List<ArchivalInsert>
    ) {
        val entry = Entry(
            timestamp = Instant.now().epochSecond,
            sessionId = sessionId,
            source = source,
            coreUpdates = coreUpdates,
            archivalInserts = archivalInserts
        )

        try {
            logFile.parentFile?.mkdirs()
            val line = json.encodeToString(entry) + "\n"
            logFile.appendText(line)
            trimIfNeeded()
        } catch (_: Exception) {
            // Best-effort — logging failure must not break the agent
        }
    }

    /** Load up to [limit] most-recent entries, newest first. */
    @Synchronized
    fun loadRecent(limit: Int): List<Entry> {
        if (!logFile.exists()) return emptyList()
        return try {
            logFile.readLines()
                .filter { it.isNotBlank() }
                .mapNotNull { line ->
                    try {
                        json.decodeFromString<Entry>(line)
                    } catch (_: Exception) {
                        null
                    }
                }
                .asReversed() // Newest first
                .take(limit)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Clear the log entirely. */
    @Synchronized
    fun clear() {
        try {
            if (logFile.exists()) logFile.delete()
        } catch (_: Exception) {
            // Best-effort
        }
    }

    /** Trim to MAX_ENTRIES if the file exceeds the cap. */
    private fun trimIfNeeded() {
        try {
            val lines = logFile.readLines()
            if (lines.size > MAX_ENTRIES) {
                val keep = lines.takeLast(MAX_ENTRIES)
                val tempFile = File(logFile.parent, "${logFile.name}.tmp")
                tempFile.writeText(keep.joinToString("\n") + "\n")
                tempFile.renameTo(logFile)
            }
        } catch (_: Exception) {
            // Best-effort — trim failure is non-fatal
        }
    }
}

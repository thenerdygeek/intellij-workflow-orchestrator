package com.workflow.orchestrator.agent.runtime

import com.intellij.openapi.diagnostic.Logger
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Append-only ledger of every file change made by the agent in a session.
 * Single source of truth for edit tracking — feeds UI, LLM context anchor,
 * and checkpoint metadata.
 *
 * COMPRESSION: The ledger itself is NOT in the LLM context window.
 * Instead, it renders a compact summary into changeLedgerAnchor
 * (via toContextString()) which IS compression-proof. The full
 * ledger is persisted to changes.jsonl for historical queries
 * via the list_changes tool.
 */
class ChangeLedger(private val sessionDir: File? = null) {

    companion object {
        private val LOG = Logger.getInstance(ChangeLedger::class.java)
        private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

        /**
         * COMPRESSION: Max entries rendered in the context anchor.
         * Beyond this, oldest entries are dropped from the anchor
         * (but remain in changes.jsonl on disk for list_changes queries).
         * At ~50-100 tokens per entry, 50 entries = ~2.5-5K tokens.
         * This is ~2.5% of a 190K context window — acceptable overhead
         * for complete change awareness.
         */
        const val MAX_ANCHOR_ENTRIES = 50

        /** Max chars for old/new string previews in ChangeEntry.
         *  COMPRESSION: Previews are rendered in the anchor table.
         *  200 chars ≈ 50 tokens per preview. Keeping short preserves
         *  token budget while giving the LLM enough context to
         *  understand what changed without re-reading the file. */
        const val MAX_PREVIEW_CHARS = 200
    }

    private val entries = CopyOnWriteArrayList<ChangeEntry>()
    private val checkpoints = mutableMapOf<String, CheckpointMeta>()
    private var changesFile: File? = null

    fun initialize(sessionDirectory: File?) {
        changesFile = sessionDirectory?.let { File(it, "changes.jsonl") }
    }

    /**
     * Record a file change. Called by EditFileTool/CreateFileTool after
     * a successful write.
     */
    fun recordChange(entry: ChangeEntry) {
        entries.add(entry)
        // Persist immediately (append-only JSONL)
        try {
            changesFile?.appendText(json.encodeToString(entry) + "\n")
        } catch (e: Exception) {
            LOG.warn("ChangeLedger: failed to persist change entry", e)
        }
    }

    /**
     * Record a checkpoint that groups related changes.
     */
    fun recordCheckpoint(meta: CheckpointMeta) {
        checkpoints[meta.id] = meta
    }

    /** Get all changes for a specific file. */
    fun changesForFile(filePath: String): List<ChangeEntry> =
        entries.filter { it.filePath == filePath || it.relativePath == filePath }

    /** Get all changes for a specific iteration. */
    fun changesForIteration(iteration: Int): List<ChangeEntry> =
        entries.filter { it.iteration == iteration }

    /** Get cumulative stats. */
    fun totalStats(): EditStats {
        val added = entries.sumOf { it.linesAdded }
        val removed = entries.sumOf { it.linesRemoved }
        val files = entries.map { it.filePath }.distinct().size
        return EditStats(added, removed, files)
    }

    /** Get per-file stats. */
    fun fileStats(): Map<String, FileEditSummary> {
        return entries.groupBy { it.relativePath }.mapValues { (path, edits) ->
            FileEditSummary(
                path = path,
                editCount = edits.size,
                totalLinesAdded = edits.sumOf { it.linesAdded },
                totalLinesRemoved = edits.sumOf { it.linesRemoved },
                lastIteration = edits.maxOf { it.iteration },
                verified = edits.lastOrNull()?.verified ?: false,
                action = edits.first().action
            )
        }
    }

    /** Mark a file's latest change as verified (called by SelfCorrectionGate). */
    fun markVerified(filePath: String, passed: Boolean, error: String? = null) {
        val latest = entries.lastOrNull { it.filePath == filePath } ?: return
        val idx = entries.indexOf(latest)
        if (idx >= 0) {
            entries[idx] = latest.copy(verified = passed, verificationError = error)
        }
    }

    /** Get all checkpoints ordered by iteration. */
    fun listCheckpoints(): List<CheckpointMeta> =
        checkpoints.values.sortedBy { it.iteration }

    /** Get all entries (for UI). */
    fun allEntries(): List<ChangeEntry> = entries.toList()

    /**
     * Render a compact context string for the LLM.
     *
     * COMPRESSION: This string becomes the changeLedgerAnchor in
     * ContextManager — a compression-proof system message. It must be:
     * 1. Compact (token-efficient) — table format, no prose
     * 2. Complete (shows all files, stats, checkpoint IDs)
     * 3. Actionable (LLM can use checkpoint IDs in rollback_changes)
     *
     * Token budget: ~50-100 tokens per file entry. At MAX_ANCHOR_ENTRIES=50,
     * worst case ~5K tokens. Typical session (5-10 files): ~500-1K tokens.
     */
    fun toContextString(): String {
        if (entries.isEmpty()) return ""

        val stats = fileStats()
        val totalStats = totalStats()
        val recentCheckpoint = checkpoints.values.maxByOrNull { it.iteration }

        return buildString {
            appendLine("Changes made in this session:")
            appendLine()

            // Per-file summary (deduplicated, shows cumulative stats)
            // COMPRESSION: Only show latest MAX_ANCHOR_ENTRIES files.
            // Oldest are dropped from anchor but remain on disk.
            val displayStats = if (stats.size > MAX_ANCHOR_ENTRIES) {
                appendLine("[Showing ${MAX_ANCHOR_ENTRIES} most recent of ${stats.size} files]")
                stats.entries.sortedByDescending { it.value.lastIteration }.take(MAX_ANCHOR_ENTRIES)
                    .associate { it.key to it.value }
            } else stats

            displayStats.entries.sortedBy { it.value.lastIteration }.forEach { (path, summary) ->
                val action = when (summary.action) {
                    ChangeAction.CREATED -> "NEW"
                    ChangeAction.MODIFIED -> "MOD"
                    ChangeAction.DELETED -> "DEL"
                }
                val verified = if (summary.verified) " ✓" else ""
                val edits = if (summary.editCount > 1) " (${summary.editCount} edits)" else ""
                appendLine("  [$action] $path +${summary.totalLinesAdded}/-${summary.totalLinesRemoved}$edits$verified")
            }

            appendLine()
            appendLine("Totals: ${totalStats.filesModified} files, +${totalStats.totalLinesAdded}/-${totalStats.totalLinesRemoved} lines")

            if (recentCheckpoint != null) {
                appendLine("Latest checkpoint: ${recentCheckpoint.id} (Iteration ${recentCheckpoint.iteration})")
                appendLine("Use rollback_changes(checkpoint_id=\"${recentCheckpoint.id}\") to revert.")
            }

            val allCheckpointIds = checkpoints.values.sortedBy { it.iteration }
            if (allCheckpointIds.size > 1) {
                appendLine("All checkpoints: ${allCheckpointIds.joinToString(", ") { "${it.id} (iter ${it.iteration})" }}")
            }
        }.trimEnd()
    }

    /**
     * Load ledger from disk (for session resume).
     */
    fun loadFromDisk() {
        val file = changesFile ?: return
        if (!file.exists()) return
        try {
            file.readLines().forEach { line ->
                if (line.isNotBlank()) {
                    try {
                        entries.add(json.decodeFromString<ChangeEntry>(line))
                    } catch (_: Exception) { /* skip malformed lines */ }
                }
            }
            LOG.info("ChangeLedger: loaded ${entries.size} entries from disk")
        } catch (e: Exception) {
            LOG.warn("ChangeLedger: failed to load from disk", e)
        }
    }
}

@Serializable
data class ChangeEntry(
    val id: String,
    val sessionId: String,
    val iteration: Int,
    val timestamp: Long,
    val filePath: String,
    val relativePath: String,
    val toolName: String,
    val action: ChangeAction,
    val linesAdded: Int,
    val linesRemoved: Int,
    val linesBefore: Int,
    val linesAfter: Int,
    /** COMPRESSION: Capped at MAX_PREVIEW_CHARS to control anchor token cost. */
    val oldPreview: String,
    /** COMPRESSION: Capped at MAX_PREVIEW_CHARS to control anchor token cost. */
    val newPreview: String,
    val editLineRange: String,
    val checkpointId: String,
    val verified: Boolean = false,
    val verificationError: String? = null
)

@Serializable
enum class ChangeAction { CREATED, MODIFIED, DELETED }

@Serializable
data class CheckpointMeta(
    val id: String,
    val description: String,
    val iteration: Int,
    val timestamp: Long,
    val filesModified: List<String>,
    val totalLinesAdded: Int,
    val totalLinesRemoved: Int
)

data class EditStats(
    val totalLinesAdded: Int,
    val totalLinesRemoved: Int,
    val filesModified: Int
)

data class FileEditSummary(
    val path: String,
    val editCount: Int,
    val totalLinesAdded: Int,
    val totalLinesRemoved: Int,
    val lastIteration: Int,
    val verified: Boolean,
    val action: ChangeAction
)

package com.workflow.orchestrator.agent.context

/**
 * Tracks files the agent has recently interacted with.
 * Provides a compact summary that can be injected into context
 * to reduce redundant file reads.
 *
 * Uses a LinkedHashMap with access-order to maintain LRU eviction.
 */
class WorkingSet(private val maxFiles: Int = 10) {

    data class FileEntry(
        val path: String,
        val lineCount: Int,
        val lastAccessed: Long = System.currentTimeMillis(),
        val wasEdited: Boolean = false,
        /** First few lines as preview (avoids re-reading for simple queries). */
        val preview: String = ""
    )

    private val files = LinkedHashMap<String, FileEntry>(maxFiles, 0.75f, true) // LRU order

    /** Record that a file was read. */
    fun recordRead(path: String, lineCount: Int, preview: String = "") {
        files[path] = FileEntry(
            path = path,
            lineCount = lineCount,
            lastAccessed = System.currentTimeMillis(),
            wasEdited = files[path]?.wasEdited ?: false,
            preview = preview.take(500)
        )
        evict()
    }

    /** Record that a file was edited. */
    fun recordEdit(path: String) {
        val existing = files[path]
        if (existing != null) {
            files[path] = existing.copy(wasEdited = true, lastAccessed = System.currentTimeMillis())
        } else {
            files[path] = FileEntry(path = path, lineCount = 0, wasEdited = true)
        }
        evict()
    }

    /** Get a compact summary for injection into LLM context. */
    fun getSummary(): String {
        if (files.isEmpty()) return ""
        val lines = files.values.sortedByDescending { it.lastAccessed }.map { entry ->
            val editMark = if (entry.wasEdited) " [EDITED]" else ""
            val fileName = entry.path.substringAfterLast('/')
            "$fileName (${entry.lineCount} lines)$editMark"
        }
        return "Working files: ${lines.joinToString(", ")}"
    }

    /** Get list of edited file paths. */
    fun getEditedFiles(): List<String> = files.values.filter { it.wasEdited }.map { it.path }

    /** Get all file entries, most recently accessed first. */
    fun getFiles(): List<FileEntry> = files.values.sortedByDescending { it.lastAccessed }

    /** Number of tracked files. */
    val size: Int get() = files.size

    /** Whether any files are tracked. */
    fun isEmpty(): Boolean = files.isEmpty()

    /** Clear all entries. */
    fun clear() = files.clear()

    private fun evict() {
        while (files.size > maxFiles) {
            val oldest = files.entries.first()
            files.remove(oldest.key)
        }
    }
}

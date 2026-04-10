package com.workflow.orchestrator.agent.memory

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant

/**
 * Tier 2: Archival Memory — long-term searchable knowledge store.
 *
 * Port of Letta's archival memory (passage_manager.py, agent_manager.py)
 * with Goose's file-based storage and Codex's usage tracking/decay.
 *
 * Storage: JSON file at ~/.workflow-orchestrator/{proj}/agent/archival/store.json
 * Search: Keyword matching with 3x tag boost (no embeddings — spec requirement).
 * Decay: Entries unused for maxUnusedDays get pruned (Codex pattern).
 * Cap: 5000 entries, oldest evicted when full.
 *
 * Letta uses PostgreSQL + pgvector. We use JSON files for zero external dependencies.
 * Codex tracks usage_count/last_usage per memory — we do the same.
 */
class ArchivalMemory(private val storageFile: File) {

    companion object {
        const val MAX_ENTRIES = 5_000
        const val DEFAULT_MAX_UNUSED_DAYS = 30L
        const val TAG_BOOST_MULTIPLIER = 3

        private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

        fun forProject(agentDir: File): ArchivalMemory {
            val archivalDir = File(agentDir, "archival")
            return ArchivalMemory(File(archivalDir, "store.json"))
        }
    }

    private var entries: MutableList<ArchivalEntry> = mutableListOf()

    init {
        load()
    }

    /**
     * Insert a memory with tags.
     *
     * Port of Letta's archival_memory_insert (core_tool_executor.py:307-317).
     * Adds Codex-style usage_count=0 and createdAt timestamp.
     *
     * @param content the memory text
     * @param tags categorization tags (e.g., ["error_resolution", "spring", "cors"])
     * @return the created entry ID
     */
    fun insert(content: String, tags: List<String> = emptyList()): String {
        // Evict oldest if at capacity (spec: cap 5000, oldest evicted)
        while (entries.size >= MAX_ENTRIES) {
            val oldest = entries.minByOrNull { it.createdAt } ?: break
            entries.remove(oldest)
        }

        val entry = ArchivalEntry(
            id = generateId(),
            content = content,
            tags = tags.map { it.lowercase().trim() }.distinct(),
            createdAt = Instant.now().epochSecond,
            usageCount = 0,
            lastUsage = null
        )
        entries.add(entry)
        persist()
        return entry.id
    }

    /**
     * Search archival memory by keyword with tag boosting.
     *
     * Port of Letta's search_agent_archival_memory_async (agent_manager.py:2534-2670)
     * simplified: keyword matching instead of vector similarity + FTS hybrid.
     *
     * Scoring (per spec): keyword match in content = 1 point per hit,
     * keyword match in tags = 3 points per hit (TAG_BOOST_MULTIPLIER).
     *
     * Codex addition: records usage on matched entries (usage_count++, last_usage=now).
     *
     * @param query search query (split into keywords)
     * @param tags optional tag filter (entries must have at least one matching tag)
     * @param limit max results (default 10)
     * @return matched entries sorted by relevance score descending
     */
    fun search(query: String, tags: List<String>? = null, limit: Int = 10): List<SearchResult> {
        val keywords = query.lowercase().split("\\s+".toRegex()).filter { it.isNotBlank() }
        if (keywords.isEmpty() && tags.isNullOrEmpty()) return emptyList()

        val filterTags = tags?.map { it.lowercase().trim() }

        val scored = entries.mapNotNull { entry ->
            // Tag filter: if specified, entry must match at least one
            if (filterTags != null && filterTags.none { it in entry.tags }) return@mapNotNull null

            var score = 0
            for (kw in keywords) {
                // Content matches (non-overlapping, case-insensitive via MemoryKeywordSearch)
                val contentHits = MemoryKeywordSearch.countOccurrences(entry.content, kw)
                score += contentHits

                // Tag matches (3x boost per spec)
                val tagHits = entry.tags.count { it.contains(kw) }
                score += tagHits * TAG_BOOST_MULTIPLIER
            }

            if (score > 0 || (keywords.isEmpty() && filterTags != null)) {
                SearchResult(entry = entry, score = if (keywords.isEmpty()) 1 else score)
            } else null
        }
            .sortedByDescending { it.score }
            .take(limit)

        // Record usage on matched entries (Codex pattern)
        val now = Instant.now().epochSecond
        for (result in scored) {
            result.entry.usageCount++
            result.entry.lastUsage = now
        }
        if (scored.isNotEmpty()) persist()

        return scored
    }

    /**
     * Prune entries unused for more than maxUnusedDays.
     *
     * Port of Codex's prune_stage1_outputs_for_retention.
     * Retention metric: COALESCE(lastUsage, createdAt).
     */
    fun prune(maxUnusedDays: Long = DEFAULT_MAX_UNUSED_DAYS): Int {
        val cutoff = Instant.now().epochSecond - (maxUnusedDays * 86400)
        val before = entries.size
        entries.removeAll { entry ->
            val recency = entry.lastUsage ?: entry.createdAt
            recency < cutoff
        }
        val removed = before - entries.size
        if (removed > 0) persist()
        return removed
    }

    /**
     * Remove all entries. Used by settings page "Clear Archival Memory" button.
     */
    fun clear() {
        entries.clear()
        persist()
    }

    /**
     * Get all entries (for debugging/export).
     */
    fun all(): List<ArchivalEntry> = entries.toList()

    /**
     * Entry count.
     */
    fun size(): Int = entries.size

    // ---- Persistence ----

    private fun load() {
        if (!storageFile.exists()) {
            entries = mutableListOf()
            return
        }
        try {
            val content = storageFile.readText()
            val stored = json.decodeFromString<StoredArchival>(content)
            entries = stored.entries.toMutableList()
        } catch (e: Exception) {
            entries = mutableListOf()
        }
    }

    private fun persist() {
        try {
            storageFile.parentFile?.mkdirs()
            val stored = StoredArchival(entries = entries.toList())
            val tempFile = File(storageFile.parent, "${storageFile.name}.tmp")
            tempFile.writeText(json.encodeToString(stored))
            tempFile.renameTo(storageFile)
        } catch (e: Exception) {
            // Best-effort persistence
        }
    }

    private fun generateId(): String = "mem_${System.currentTimeMillis()}_${(Math.random() * 10000).toInt()}"

    // ---- Data classes ----

    @Serializable
    data class ArchivalEntry(
        val id: String,
        val content: String,
        val tags: List<String>,
        val createdAt: Long,
        var usageCount: Int = 0,
        var lastUsage: Long? = null
    )

    data class SearchResult(
        val entry: ArchivalEntry,
        val score: Int
    )

    @Serializable
    private data class StoredArchival(val entries: List<ArchivalEntry>)
}

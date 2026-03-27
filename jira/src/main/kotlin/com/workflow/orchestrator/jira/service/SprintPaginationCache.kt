package com.workflow.orchestrator.jira.service

import com.intellij.openapi.diagnostic.Logger
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * File-based cache for sprint pagination state, stored in the user's home directory.
 * Shared across all IDE instances so the expensive full-walk only happens once.
 *
 * Cache file: ~/.workflow-orchestrator/sprint-pagination-cache.json
 *
 * Stores per-board: the last known startAt offset where we found the final page
 * of closed sprints. On subsequent calls, we start from (cachedStartAt - pageSize)
 * to catch any newly closed sprints, instead of walking from 0.
 *
 * Thread-safe: all access is synchronized since multiple projects/coroutines
 * may call concurrently.
 */
object SprintPaginationCache {

    private val log = Logger.getInstance(SprintPaginationCache::class.java)
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private val cacheDir = File(System.getProperty("user.home"), ".workflow-orchestrator")
    private val cacheFile = File(cacheDir, "sprint-pagination-cache.json")

    @Serializable
    data class BoardCacheEntry(
        val boardId: Int,
        val lastStartAt: Int,
        val timestamp: Long = System.currentTimeMillis()
    )

    @Serializable
    data class CacheData(
        val boards: MutableMap<String, BoardCacheEntry> = mutableMapOf()
    )

    private val lock = Any()
    @Volatile
    private var cache: CacheData? = null

    /**
     * Get the cached startAt offset for a board.
     * Returns 0 if no cache exists (first walk).
     */
    fun getCachedStartAt(boardId: Int, pageSize: Int): Int {
        synchronized(lock) {
            val data = loadCacheLocked()
            val entry = data.boards[boardId.toString()] ?: return 0
            return (entry.lastStartAt - pageSize).coerceAtLeast(0)
        }
    }

    /**
     * Save the startAt offset of the last page for a board.
     */
    fun saveCachedStartAt(boardId: Int, lastStartAt: Int) {
        synchronized(lock) {
            val data = loadCacheLocked()
            data.boards[boardId.toString()] = BoardCacheEntry(
                boardId = boardId,
                lastStartAt = lastStartAt
            )
            saveCacheLocked(data)
        }
    }

    /** Must be called under [lock]. */
    private fun loadCacheLocked(): CacheData {
        cache?.let { return it }
        return try {
            if (cacheFile.exists()) {
                val loaded = json.decodeFromString<CacheData>(cacheFile.readText())
                cache = loaded
                loaded
            } else {
                val empty = CacheData()
                cache = empty
                empty
            }
        } catch (e: Exception) {
            log.warn("[SprintCache] Failed to read cache: ${e.message}")
            val empty = CacheData()
            cache = empty
            empty
        }
    }

    /** Must be called under [lock]. */
    private fun saveCacheLocked(data: CacheData) {
        try {
            cacheDir.mkdirs()
            cacheFile.writeText(json.encodeToString(CacheData.serializer(), data))
            cache = data
        } catch (e: Exception) {
            log.warn("[SprintCache] Failed to write cache: ${e.message}")
        }
    }
}

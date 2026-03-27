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
 * Thread-safe: in-memory cache access is synchronized. File I/O runs outside
 * the lock to avoid blocking concurrent callers on slow disk operations.
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
    private var cache: CacheData? = null

    /**
     * Get the cached startAt offset for a board.
     * Returns 0 if no cache exists (first walk).
     */
    fun getCachedStartAt(boardId: Int, pageSize: Int): Int {
        val data = getOrLoadCache()
        synchronized(lock) {
            val entry = data.boards[boardId.toString()] ?: return 0
            return (entry.lastStartAt - pageSize).coerceAtLeast(0)
        }
    }

    /**
     * Save the startAt offset of the last page for a board.
     * In-memory update is synchronized; file write happens outside the lock.
     */
    fun saveCachedStartAt(boardId: Int, lastStartAt: Int) {
        val snapshot: CacheData
        synchronized(lock) {
            val data = getOrLoadCache()
            data.boards[boardId.toString()] = BoardCacheEntry(
                boardId = boardId,
                lastStartAt = lastStartAt
            )
            snapshot = data
            cache = data
        }
        // File write outside lock to avoid blocking concurrent readers
        writeToDisk(snapshot)
    }

    /** Load cache from disk if not yet in memory. Thread-safe. */
    private fun getOrLoadCache(): CacheData {
        synchronized(lock) {
            cache?.let { return it }
        }
        // Read from disk outside lock
        val loaded = readFromDisk()
        synchronized(lock) {
            // Double-check: another thread may have loaded while we read
            cache?.let { return it }
            cache = loaded
            return loaded
        }
    }

    private fun readFromDisk(): CacheData {
        return try {
            if (cacheFile.exists()) {
                json.decodeFromString<CacheData>(cacheFile.readText())
            } else {
                CacheData()
            }
        } catch (e: Exception) {
            log.warn("[SprintCache] Failed to read cache: ${e.message}")
            CacheData()
        }
    }

    private fun writeToDisk(data: CacheData) {
        try {
            cacheDir.mkdirs()
            cacheFile.writeText(json.encodeToString(CacheData.serializer(), data))
        } catch (e: Exception) {
            log.warn("[SprintCache] Failed to write cache: ${e.message}")
        }
    }
}

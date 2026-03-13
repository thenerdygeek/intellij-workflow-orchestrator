package com.workflow.orchestrator.jira.vcs

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque

data class TicketCacheEntry(
    val key: String,
    val summary: String,
    val statusName: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Thread-safe LRU cache with TTL for Jira ticket metadata.
 * Used by VCS Log Column and other integrations that need ticket details
 * without hitting the API on every render.
 */
class TicketCache(
    private val maxSize: Int = 500,
    private val ttlMs: Long = 600_000 // 10 minutes
) {
    private val map = ConcurrentHashMap<String, TicketCacheEntry>()
    private val accessOrder = ConcurrentLinkedDeque<String>()

    fun get(key: String): TicketCacheEntry? {
        val entry = map[key] ?: return null
        if (System.currentTimeMillis() - entry.timestamp > ttlMs) {
            map.remove(key)
            accessOrder.remove(key)
            return null
        }
        // Move to end (most recently used)
        accessOrder.remove(key)
        accessOrder.addLast(key)
        return entry
    }

    fun put(key: String, entry: TicketCacheEntry) {
        map[key] = entry
        accessOrder.remove(key)
        accessOrder.addLast(key)
        while (map.size > maxSize) {
            val oldest = accessOrder.pollFirst() ?: break
            map.remove(oldest)
        }
    }

    fun clear() {
        map.clear()
        accessOrder.clear()
    }
}

/** Extracts Jira ticket ID from commit messages. */
object TicketIdExtractor {
    private val TICKET_PATTERN = Regex("\\b([A-Z][A-Z0-9]+-\\d+)\\b")

    fun extract(commitMessage: String): String? {
        return TICKET_PATTERN.find(commitMessage)?.groupValues?.get(1)
    }
}

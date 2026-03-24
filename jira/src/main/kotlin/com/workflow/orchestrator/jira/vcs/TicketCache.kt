package com.workflow.orchestrator.jira.vcs

data class TicketCacheEntry(
    val key: String,
    val summary: String,
    val statusName: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Thread-safe LRU cache with TTL for Jira ticket metadata.
 * Uses LinkedHashMap(accessOrder=true) for O(1) LRU eviction.
 * All access is synchronized for thread safety.
 *
 * Previous implementation used ConcurrentHashMap + ConcurrentLinkedDeque which had
 * O(n) removal on every get() call — a performance issue when the cache grows.
 */
class TicketCache(
    private val maxSize: Int = 500,
    private val ttlMs: Long = 600_000 // 10 minutes
) {
    private val map = object : LinkedHashMap<String, TicketCacheEntry>(maxSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TicketCacheEntry>): Boolean {
            return size > maxSize
        }
    }

    @Synchronized
    fun get(key: String): TicketCacheEntry? {
        val entry = map[key] ?: return null
        if (System.currentTimeMillis() - entry.timestamp > ttlMs) {
            map.remove(key)
            return null
        }
        return entry
    }

    @Synchronized
    fun put(key: String, entry: TicketCacheEntry) {
        map[key] = entry
    }

    @Synchronized
    fun clear() {
        map.clear()
    }
}

/** Extracts Jira ticket ID from commit messages. */
object TicketIdExtractor {
    private val TICKET_PATTERN = Regex("\\b([A-Z][A-Z0-9]+-\\d+)\\b")

    fun extract(commitMessage: String): String? {
        return TICKET_PATTERN.find(commitMessage)?.groupValues?.get(1)
    }
}

package com.workflow.orchestrator.core.http

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.RemovalCause

/**
 * In-memory HTTP response cache for Phase 3 Prong A.
 *
 * Keyed by a composite string (method + canonicalised URL + hash of Authorization +
 * Accept header) — see [CacheKey] for construction. Value is [Entry] holding the
 * raw body bytes, a SHA-256 fingerprint of those bytes, and TTL bookkeeping.
 *
 * Backed by Caffeine with weight-based eviction: the weight of an entry is the size
 * of its body bytes, so the total memory footprint is bounded by [DEFAULT_MAX_BYTES]
 * regardless of how many entries are stored.
 *
 * Scope: a singleton `object`. The plugin has a single ServiceType-indexed HTTP
 * client family per project, and caching is safe across projects (keys include the
 * auth fingerprint, which differs per project). Callers acquire a reference with
 * `HttpResponseCache` — no getInstance dance.
 *
 * Metrics: every put/eviction updates [HttpCacheMetrics] so the per-service
 * [HttpCacheMetrics.CacheStats.bytesInCache] / [entriesInCache] fields reflect the
 * live cache footprint.
 *
 * Thundering-herd note: v1 uses a plain [Cache], not [com.github.benmanes.caffeine.cache.AsyncLoadingCache].
 * Simultaneous cache misses will each perform their own network call; the worst
 * case is `N` parallel pollers all missing on the same key at once and all doing
 * redundant HTTP. Measured via [HttpCacheMetrics] — if hit rate on a hot key is
 * suspiciously low, upgrade to a `Map<CacheKey, CompletableFuture<Entry>>` coalescer.
 */
object HttpResponseCache {

    const val DEFAULT_MAX_BYTES: Long = 5L * 1024 * 1024
    const val DEFAULT_MAX_ENTRIES: Long = 500L

    /**
     * Cached HTTP response payload.
     *
     * ByteArray equality is reference-based by default, which is fine here because
     * [Entry] is only used as a value in the cache — we never compare entries with
     * `==`, only look them up by key.
     */
    class Entry(
        val bodyBytes: ByteArray,
        val sha256: ByteArray,
        val contentType: String?,
        val statusCode: Int,
        val tag: String,
        val storedAtMillis: Long,
        val ttlSeconds: Long
    ) {
        val ageMillis: Long get() = System.currentTimeMillis() - storedAtMillis
        val isFresh: Boolean get() = ageMillis < ttlSeconds * 1000L
    }

    private val cache: Cache<CacheKey, Entry> = Caffeine.newBuilder()
        .maximumWeight(DEFAULT_MAX_BYTES)
        .weigher<CacheKey, Entry> { _, entry -> entry.bodyBytes.size }
        .removalListener<CacheKey, Entry> { _, entry, cause ->
            if (entry != null && cause != null && cause.wasEvicted()) {
                HttpCacheMetrics.recordEviction(entry.tag)
                HttpCacheMetrics.updateBytes(entry.tag, -entry.bodyBytes.size.toLong())
                HttpCacheMetrics.updateEntries(entry.tag, -1L)
            }
        }
        .build()

    fun get(key: CacheKey): Entry? = cache.getIfPresent(key)

    fun put(key: CacheKey, entry: Entry) {
        val existing = cache.getIfPresent(key)
        if (existing != null) {
            HttpCacheMetrics.updateBytes(entry.tag, (entry.bodyBytes.size - existing.bodyBytes.size).toLong())
        } else {
            HttpCacheMetrics.updateBytes(entry.tag, entry.bodyBytes.size.toLong())
            HttpCacheMetrics.updateEntries(entry.tag, 1L)
        }
        cache.put(key, entry)
    }

    /** @return `true` if an entry was present and removed. */
    fun invalidate(key: CacheKey): Boolean {
        val existing = cache.getIfPresent(key) ?: return false
        cache.invalidate(key)
        HttpCacheMetrics.updateBytes(existing.tag, -existing.bodyBytes.size.toLong())
        HttpCacheMetrics.updateEntries(existing.tag, -1L)
        return true
    }

    /**
     * Invalidates every entry whose key value starts with [prefix]. Used by mutation
     * invalidation — e.g., after `POST /issue/PROJ-1/transitions`, evict every
     * cached `GET /issue/PROJ-1…`. Returns the number of entries removed.
     */
    fun invalidateByPrefix(prefix: String): Int {
        val matches = cache.asMap().keys.filter { it.value.contains(prefix) }
        matches.forEach { invalidate(it) }
        return matches.size
    }

    fun invalidateAll() {
        cache.asMap().values.groupBy { it.tag }.forEach { (tag, entries) ->
            val totalBytes = entries.sumOf { it.bodyBytes.size.toLong() }
            HttpCacheMetrics.updateBytes(tag, -totalBytes)
            HttpCacheMetrics.updateEntries(tag, -entries.size.toLong())
        }
        cache.invalidateAll()
    }

    fun estimatedSize(): Long = cache.estimatedSize()

    /** Test-only. Drops all entries and forces eviction listener processing. */
    internal fun clearForTest() {
        cache.invalidateAll()
        cache.cleanUp()
    }
}

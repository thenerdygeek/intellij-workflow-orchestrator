package com.workflow.orchestrator.web.service.cache

import com.workflow.orchestrator.core.model.web.WebPage
import java.time.Duration
import java.time.Instant
import java.util.Collections
import java.util.LinkedHashMap

/**
 * In-memory LRU+TTL cache for [WebPage] results.
 *
 * Lifetime: instance-scoped. Production wiring constructs one cache per engine
 * (one engine per project @Service), so the cache lives as long as the project is open
 * and disappears on IDE restart. Intentional — survives long-running sessions (where doc
 * lookups repeat) without the disk-cache complexity (stale-after-allowlist-change,
 * audit-on-cached-injection-content, re-sanitize-on-read).
 *
 * Key: (url, maxBytes, sanitizerBrainId). Different brain ID or byte cap could yield
 * different cleaned text, so must not share a slot.
 *
 * Thread-safety: synchronized LinkedHashMap in access-order mode. Adequate for the
 * concurrency profile (per-project, dozens of fetches/min peak). TTL checked lazily on get.
 */
class WebFetchCache(
    maxEntries: Int,
    private val ttl: Duration,
    private val clock: () -> Instant = { Instant.now() },
) {

    data class Key(
        val url: String,
        val maxBytes: Int?,
        val sanitizerBrainId: String?,
    )

    private data class Entry(val page: WebPage, val cachedAt: Instant)

    private val store: MutableMap<Key, Entry> = Collections.synchronizedMap(
        object : LinkedHashMap<Key, Entry>(maxEntries, 0.75f, /*accessOrder=*/ true) {
            override fun removeEldestEntry(eldest: Map.Entry<Key, Entry>): Boolean = size > maxEntries
        }
    )

    fun get(key: Key): WebPage? {
        val entry = synchronized(store) { store[key] } ?: return null
        if (clock().isAfter(entry.cachedAt.plus(ttl))) {
            synchronized(store) { store.remove(key) }
            return null
        }
        return entry.page
    }

    fun put(key: Key, page: WebPage) {
        synchronized(store) { store[key] = Entry(page, clock()) }
    }

    fun size(): Int = synchronized(store) { store.size }
}

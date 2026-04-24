package com.workflow.orchestrator.core.http

import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Scaffolding for Phase 3 HTTP response caching observability.
 *
 * Sibling to [HttpMetricsRegistry] but tracks a different dimension — request
 * caching rather than request latency. Counters are per-service (using the same
 * service tag produced by [HttpMetricsInterceptor.ServiceUrlTagger]) so we can
 * tell Jira hit-rate from Bamboo hit-rate from Bitbucket hit-rate.
 *
 * The concrete caching interceptor is landed in a later commit in the Phase 3
 * Prong A sequence. This registry exists first so we can count from the activation
 * commit onward and have pre-activation metrics (all misses, 0% hit rate) as a
 * natural baseline — no separate `phase3-baseline.md` document needed. See
 * `docs/architecture/phase3-caching-strategy.md` §8 for the measurement policy.
 */
object HttpCacheMetrics {

    /**
     * Outcome of a request when it passes through the caching interceptor.
     *
     * - [HIT_FRESH]: served from cache without any network call; TTL not expired.
     * - [HIT_STALE_MATCH]: refetched from origin; body hash matched the cached
     *   copy, so we reused the already-parsed domain object and skipped Jackson.
     * - [HIT_STALE_DIFFER]: refetched; body hash differed; reparsed.
     * - [MISS]: not in cache; fetched and stored.
     */
    enum class Outcome { HIT_FRESH, HIT_STALE_MATCH, HIT_STALE_DIFFER, MISS }

    @Serializable
    data class CacheStats(
        val hitFresh: Long,
        val hitStaleMatch: Long,
        val hitStaleDiffer: Long,
        val miss: Long,
        val evicted: Long,
        val invalidatedByMutation: Long,
        val bytesInCache: Long,
        val entriesInCache: Long
    ) {
        val total: Long get() = hitFresh + hitStaleMatch + hitStaleDiffer + miss
        val hits: Long get() = hitFresh + hitStaleMatch + hitStaleDiffer

        /** Percentage of requests that avoided a Jackson parse. 0.0 when total == 0. */
        val hitRatePct: Double
            get() = if (total == 0L) 0.0 else 100.0 * hits / total
    }

    private val hitFresh = ConcurrentHashMap<String, AtomicLong>()
    private val hitStaleMatch = ConcurrentHashMap<String, AtomicLong>()
    private val hitStaleDiffer = ConcurrentHashMap<String, AtomicLong>()
    private val miss = ConcurrentHashMap<String, AtomicLong>()
    private val evicted = ConcurrentHashMap<String, AtomicLong>()
    private val invalidated = ConcurrentHashMap<String, AtomicLong>()
    private val bytes = ConcurrentHashMap<String, AtomicLong>()
    private val entries = ConcurrentHashMap<String, AtomicLong>()

    fun record(tag: String, outcome: Outcome) {
        when (outcome) {
            Outcome.HIT_FRESH -> hitFresh.bump(tag)
            Outcome.HIT_STALE_MATCH -> hitStaleMatch.bump(tag)
            Outcome.HIT_STALE_DIFFER -> hitStaleDiffer.bump(tag)
            Outcome.MISS -> miss.bump(tag)
        }
    }

    fun recordEviction(tag: String) = evicted.bump(tag)

    fun recordMutationInvalidation(tag: String) = invalidated.bump(tag)

    /** Delta may be positive (entry added) or negative (entry evicted / invalidated). */
    fun updateBytes(tag: String, delta: Long) {
        bytes.getOrPut(tag) { AtomicLong(0) }.addAndGet(delta)
    }

    fun updateEntries(tag: String, delta: Long) {
        entries.getOrPut(tag) { AtomicLong(0) }.addAndGet(delta)
    }

    fun getStats(tag: String): CacheStats = CacheStats(
        hitFresh = hitFresh[tag]?.get() ?: 0L,
        hitStaleMatch = hitStaleMatch[tag]?.get() ?: 0L,
        hitStaleDiffer = hitStaleDiffer[tag]?.get() ?: 0L,
        miss = miss[tag]?.get() ?: 0L,
        evicted = evicted[tag]?.get() ?: 0L,
        invalidatedByMutation = invalidated[tag]?.get() ?: 0L,
        bytesInCache = bytes[tag]?.get() ?: 0L,
        entriesInCache = entries[tag]?.get() ?: 0L
    )

    fun getAllStats(): Map<String, CacheStats> {
        val tags = (hitFresh.keys + hitStaleMatch.keys + hitStaleDiffer.keys +
            miss.keys + evicted.keys + invalidated.keys + bytes.keys + entries.keys)
        return tags.associateWith(::getStats)
    }

    fun reset() {
        hitFresh.clear()
        hitStaleMatch.clear()
        hitStaleDiffer.clear()
        miss.clear()
        evicted.clear()
        invalidated.clear()
        bytes.clear()
        entries.clear()
    }

    private fun ConcurrentHashMap<String, AtomicLong>.bump(tag: String) {
        getOrPut(tag) { AtomicLong(0) }.incrementAndGet()
    }
}

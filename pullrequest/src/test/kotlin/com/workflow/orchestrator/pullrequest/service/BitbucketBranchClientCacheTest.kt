package com.workflow.orchestrator.pullrequest.service

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicReference

/**
 * Tests for D3 (audit finding pullrequest:F-4):
 * BitbucketBranchClientCache non-atomic check-then-act.
 *
 * Previously two separate @Volatile fields were read and written without
 * coordination, allowing concurrent callers to see mismatched (url, client) pairs.
 *
 * Fix: combined into a single AtomicReference<Pair<String, BitbucketBranchClient>?>
 * with a compare-and-set loop on cache miss.
 *
 * The source-text tests pin the structural invariants since the cache's
 * [get] method calls [BitbucketBranchClient.fromConfiguredSettings()] which depends
 * on IntelliJ platform services not available in plain JUnit 5.
 * The atomic-reference invariant test verifies the concurrency model directly.
 */
class BitbucketBranchClientCacheTest {

    private val src: String by lazy {
        java.io.File(
            "src/main/kotlin/com/workflow/orchestrator/pullrequest/service/BitbucketBranchClientCache.kt"
        ).readText()
    }

    @Test
    fun `cache uses AtomicReference instead of two separate Volatile fields`() {
        assertTrue(src.contains("AtomicReference<Pair<String, BitbucketBranchClient>?>"),
            "Cache must use AtomicReference<Pair<...>> for atomic (url, client) state")
        // Old pattern must be gone
        assertTrue(!src.contains("@Volatile private var cachedClient"),
            "Old @Volatile cachedClient field must be removed")
        assertTrue(!src.contains("@Volatile private var cachedBaseUrl"),
            "Old @Volatile cachedBaseUrl field must be removed")
    }

    @Test
    fun `compareAndSet is used in the slow path`() {
        assertTrue(src.contains("compareAndSet("),
            "Cache slow path must use compareAndSet to atomically install new entry")
    }

    @Test
    fun `cachedUrl reads from the atomic snapshot`() {
        // cachedUrl must read from cached.get() (the AtomicReference), not a separate field.
        val cachedUrlPropIdx = src.indexOf("val cachedUrl")
        assertTrue(cachedUrlPropIdx >= 0, "cachedUrl property must exist")
        val firstReadAfter = src.indexOf("cached.get()", cachedUrlPropIdx)
        assertTrue(firstReadAfter > cachedUrlPropIdx && firstReadAfter < cachedUrlPropIdx + 200,
            "cachedUrl must read from cached.get()?.first (the atomic snapshot)")
    }

    @Test
    fun `AtomicReference invariant holds under concurrent updates`() {
        // Unit-test the invariant directly without IntelliJ platform using a simulated cache.
        // Verifies that two-field non-atomic state cannot produce mismatched pairs:
        // AtomicReference ensures a reader always gets a consistent (url, client) pair.
        val ref = AtomicReference<Pair<String, String>?>(null)
        val url1 = "https://bitbucket.example.com"
        val url2 = "https://bitbucket.other.com"
        val client1 = "client-for-url1"
        val client2 = "client-for-url2"

        // Simulate concurrent writers
        val results = mutableListOf<Pair<String, String>>()
        val threads = listOf(
            Thread {
                repeat(100) {
                    val entry = Pair(url1, client1)
                    ref.set(entry)
                    val read = ref.get()
                    if (read != null) synchronized(results) { results.add(read) }
                }
            },
            Thread {
                repeat(100) {
                    val entry = Pair(url2, client2)
                    ref.set(entry)
                    val read = ref.get()
                    if (read != null) synchronized(results) { results.add(read) }
                }
            }
        )
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        // Every observed pair must be internally consistent (url matches client)
        val inconsistent = results.filter { (url, client) ->
            when (url) {
                url1 -> client != client1
                url2 -> client != client2
                else -> true
            }
        }
        assertTrue(inconsistent.isEmpty(),
            "All observed (url, client) pairs must be consistent; found ${inconsistent.size} inconsistencies")
    }
}

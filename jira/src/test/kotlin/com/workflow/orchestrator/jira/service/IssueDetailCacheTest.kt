package com.workflow.orchestrator.jira.service

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Covers the LRU eviction contract introduced by audit finding jira:F-11.
 *
 * Before the fix, eviction used an O(N log N) sort on every `put` after MAX_SIZE was
 * reached. After the fix, eviction is O(1) via a synchronized access-ordered LinkedHashMap
 * with [removeEldestEntry].
 */
class IssueDetailCacheTest {

    @Test
    fun `cache size stays bounded at MAX_SIZE when over-filled`() {
        val cache = IssueDetailCache()
        val maxSize = 200

        // Fill beyond the limit
        for (i in 1..(maxSize + 50)) {
            cache.put("KEY-$i", IssueDetailCache.IssueDetailData())
        }

        // Verify that only MAX_SIZE entries remain
        var count = 0
        for (i in 1..(maxSize + 50)) {
            if (cache.get("KEY-$i") != null) count++
        }
        assertEquals(maxSize, count, "Cache must hold exactly MAX_SIZE=200 entries after overfill")
    }

    @Test
    fun `least-recently-used entry is evicted first`() {
        val cache = IssueDetailCache()
        val maxSize = 200

        // Insert exactly MAX_SIZE entries: KEY-1 ... KEY-200
        for (i in 1..maxSize) {
            cache.put("KEY-$i", IssueDetailCache.IssueDetailData())
        }

        // Access KEY-1 (promote it to MRU position)
        assertNotNull(cache.get("KEY-1"), "KEY-1 should be present before eviction trigger")

        // Inserting one more entry must evict the true LRU — which is now KEY-2
        // (KEY-1 was accessed above and is no longer the least-recently-used).
        cache.put("KEY-201", IssueDetailCache.IssueDetailData())

        assertNotNull(cache.get("KEY-1"), "KEY-1 was accessed; it must NOT be evicted")
        assertNull(cache.get("KEY-2"), "KEY-2 is the new LRU and must be evicted")
        assertNotNull(cache.get("KEY-201"), "Newly inserted entry must be present")
    }

    @Test
    fun `updateComments evicts LRU when cache is full`() {
        val cache = IssueDetailCache()
        val maxSize = 200

        // Fill to MAX_SIZE
        for (i in 1..maxSize) {
            cache.put("KEY-$i", IssueDetailCache.IssueDetailData())
        }

        // updateComments on a new key effectively inserts — must still bound the size
        cache.updateComments("KEY-NEW", emptyList())

        var count = 0
        for (i in 1..maxSize) {
            if (cache.get("KEY-$i") != null) count++
        }
        if (cache.get("KEY-NEW") != null) count++

        assertEquals(maxSize, count, "Size must stay at MAX_SIZE after updateComments on a new key")
    }

    @Test
    fun `updateAttachments evicts LRU when cache is full`() {
        val cache = IssueDetailCache()
        val maxSize = 200

        // Fill to MAX_SIZE
        for (i in 1..maxSize) {
            cache.put("KEY-$i", IssueDetailCache.IssueDetailData())
        }

        cache.updateAttachments("KEY-NEW", emptyList())

        var count = 0
        for (i in 1..maxSize) {
            if (cache.get("KEY-$i") != null) count++
        }
        if (cache.get("KEY-NEW") != null) count++

        assertEquals(maxSize, count, "Size must stay at MAX_SIZE after updateAttachments on a new key")
    }
}

package com.workflow.orchestrator.jira.vcs

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TicketCacheTest {

    @Test
    fun `stores and retrieves cached ticket`() {
        val cache = TicketCache(maxSize = 10, ttlMs = 60_000)
        cache.put("PROJ-123", TicketCacheEntry("PROJ-123", "Fix login", "In Progress"))
        val entry = cache.get("PROJ-123")
        assertNotNull(entry)
        assertEquals("Fix login", entry!!.summary)
    }

    @Test
    fun `returns null for missing ticket`() {
        val cache = TicketCache(maxSize = 10, ttlMs = 60_000)
        assertNull(cache.get("PROJ-999"))
    }

    @Test
    fun `evicts expired entries`() {
        val cache = TicketCache(maxSize = 10, ttlMs = 1) // 1ms TTL
        cache.put("PROJ-123", TicketCacheEntry("PROJ-123", "Fix login", "In Progress"))
        Thread.sleep(10)
        assertNull(cache.get("PROJ-123"))
    }

    @Test
    fun `evicts oldest when at capacity`() {
        val cache = TicketCache(maxSize = 2, ttlMs = 60_000)
        cache.put("A-1", TicketCacheEntry("A-1", "First", "Open"))
        cache.put("A-2", TicketCacheEntry("A-2", "Second", "Open"))
        cache.put("A-3", TicketCacheEntry("A-3", "Third", "Open"))
        assertNull(cache.get("A-1")) // evicted
        assertNotNull(cache.get("A-3"))
    }

    @Test
    fun `extracts ticket ID from commit message - standard prefix`() {
        assertEquals("PROJ-123", TicketIdExtractor.extract("PROJ-123: fix login"))
    }

    @Test
    fun `extracts ticket ID from commit message - conventional commit`() {
        assertEquals("PROJ-123", TicketIdExtractor.extract("feat(PROJ-123): fix login"))
    }

    @Test
    fun `extracts ticket ID from commit message - short key`() {
        assertEquals("AB-1", TicketIdExtractor.extract("AB-1 something"))
    }

    @Test
    fun `returns null when no ticket in message`() {
        assertNull(TicketIdExtractor.extract("no ticket here"))
    }

    @Test
    fun `returns null for empty message`() {
        assertNull(TicketIdExtractor.extract(""))
    }
}

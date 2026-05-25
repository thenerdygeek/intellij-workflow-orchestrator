package com.workflow.orchestrator.web.service.cache

import com.workflow.orchestrator.core.model.web.AllowlistDecision
import com.workflow.orchestrator.core.model.web.SanitizerVerdict
import com.workflow.orchestrator.core.model.web.WebPage
import com.workflow.orchestrator.core.web.UrlScreener
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class WebFetchCacheTest {

    private fun samplePage(url: String = "https://docs.example.com/x", text: String = "hello"): WebPage =
        WebPage(
            originalUrl = url,
            finalUrl = url,
            contentType = "text/html",
            responseBytes = text.length.toLong(),
            extractedText = text,
            extractedChars = text.length,
            screenerFlags = emptySet<UrlScreener.Flag>(),
            allowlistDecision = AllowlistDecision.APPROVED_AUTO,
            sanitizerVerdict = SanitizerVerdict.SAFE,
            sanitizerNotes = null,
            contentHash = "abc123",
            fetchedAt = Instant.now(),
            elapsedMs = 100,
        )

    @Test
    fun `get returns null on miss`() {
        val cache = WebFetchCache(maxEntries = 10, ttl = Duration.ofMinutes(15))
        assertNull(cache.get(WebFetchCache.Key("https://x/", null, null)))
    }

    @Test
    fun `put then get returns the stored page`() {
        val cache = WebFetchCache(maxEntries = 10, ttl = Duration.ofMinutes(15))
        val key = WebFetchCache.Key("https://x/", null, null)
        val page = samplePage("https://x/")
        cache.put(key, page)
        assertEquals(page, cache.get(key))
    }

    @Test
    fun `keys with different maxBytes are distinct`() {
        val cache = WebFetchCache(maxEntries = 10, ttl = Duration.ofMinutes(15))
        cache.put(WebFetchCache.Key("https://x/", 1000, null), samplePage(text = "small"))
        cache.put(WebFetchCache.Key("https://x/", 5000, null), samplePage(text = "big"))
        assertEquals("small", cache.get(WebFetchCache.Key("https://x/", 1000, null))?.extractedText)
        assertEquals("big",   cache.get(WebFetchCache.Key("https://x/", 5000, null))?.extractedText)
    }

    @Test
    fun `keys with different sanitizerBrainId are distinct`() {
        val cache = WebFetchCache(maxEntries = 10, ttl = Duration.ofMinutes(15))
        cache.put(WebFetchCache.Key("https://x/", null, "brain-A"), samplePage(text = "A"))
        cache.put(WebFetchCache.Key("https://x/", null, "brain-B"), samplePage(text = "B"))
        assertEquals("A", cache.get(WebFetchCache.Key("https://x/", null, "brain-A"))?.extractedText)
        assertEquals("B", cache.get(WebFetchCache.Key("https://x/", null, "brain-B"))?.extractedText)
    }

    @Test
    fun `entry past TTL is evicted on get`() {
        var now = Instant.parse("2026-05-24T12:00:00Z")
        val cache = WebFetchCache(maxEntries = 10, ttl = Duration.ofMinutes(15), clock = { now })
        val key = WebFetchCache.Key("https://x/", null, null)
        cache.put(key, samplePage())
        assertNotNull(cache.get(key))
        now = now.plus(Duration.ofMinutes(16))
        assertNull(cache.get(key))
    }

    @Test
    fun `LRU eviction drops least-recently-accessed entry when over capacity`() {
        val cache = WebFetchCache(maxEntries = 2, ttl = Duration.ofMinutes(15))
        val k1 = WebFetchCache.Key("https://1/", null, null)
        val k2 = WebFetchCache.Key("https://2/", null, null)
        val k3 = WebFetchCache.Key("https://3/", null, null)
        cache.put(k1, samplePage(text = "1"))
        cache.put(k2, samplePage(text = "2"))
        cache.get(k1)  // touch k1 → k2 becomes least-recently-accessed
        cache.put(k3, samplePage(text = "3"))  // should evict k2
        assertNotNull(cache.get(k1), "k1 was just touched, must survive")
        assertNull(cache.get(k2), "k2 was least-recently-accessed, should be evicted")
        assertNotNull(cache.get(k3))
    }

    @Test
    fun `concurrent put + get does not throw`() {
        val cache = WebFetchCache(maxEntries = 100, ttl = Duration.ofMinutes(15))
        val threads = (1..10).map { i ->
            Thread {
                repeat(50) { j ->
                    val key = WebFetchCache.Key("https://t$i-$j/", null, null)
                    cache.put(key, samplePage("https://t$i-$j/"))
                    cache.get(key)
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
    }
}

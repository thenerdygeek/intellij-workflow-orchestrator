package com.workflow.orchestrator.core.http

import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class HttpResponseCacheTest {

    @BeforeEach
    fun setUp() {
        HttpResponseCache.clearForTest()
        HttpCacheMetrics.reset()
    }

    @AfterEach
    fun tearDown() {
        HttpResponseCache.clearForTest()
        HttpCacheMetrics.reset()
    }

    private fun entry(tag: String = "jira", size: Int = 128, ttl: Long = 60): HttpResponseCache.Entry =
        HttpResponseCache.Entry(
            bodyBytes = ByteArray(size),
            sha256 = ByteArray(32),
            contentType = "application/json",
            statusCode = 200,
            tag = tag,
            storedAtMillis = System.currentTimeMillis(),
            ttlSeconds = ttl
        )

    @Test
    fun `get returns null for missing key`() {
        assertNull(HttpResponseCache.get(CacheKey("nope")))
    }

    @Test
    fun `put then get round-trips`() {
        val key = CacheKey("k1")
        val e = entry()
        HttpResponseCache.put(key, e)
        val got = HttpResponseCache.get(key)
        assertNotNull(got)
        assertEquals(128, got!!.bodyBytes.size)
    }

    @Test
    fun `put updates metrics for new entry`() {
        HttpResponseCache.put(CacheKey("k1"), entry(tag = "jira", size = 128))
        val stats = HttpCacheMetrics.getStats("jira")
        assertEquals(128L, stats.bytesInCache)
        assertEquals(1L, stats.entriesInCache)
    }

    @Test
    fun `put over existing key adjusts byte delta`() {
        val key = CacheKey("k1")
        HttpResponseCache.put(key, entry(tag = "jira", size = 100))
        HttpResponseCache.put(key, entry(tag = "jira", size = 250))
        val stats = HttpCacheMetrics.getStats("jira")
        assertEquals(250L, stats.bytesInCache)
        assertEquals(1L, stats.entriesInCache)
    }

    @Test
    fun `invalidate returns true for present key and updates metrics`() {
        val key = CacheKey("k1")
        HttpResponseCache.put(key, entry(tag = "jira", size = 100))
        val removed = HttpResponseCache.invalidate(key)
        assertTrue(removed)
        assertNull(HttpResponseCache.get(key))
        val stats = HttpCacheMetrics.getStats("jira")
        assertEquals(0L, stats.bytesInCache)
        assertEquals(0L, stats.entriesInCache)
    }

    @Test
    fun `invalidate returns false for absent key`() {
        assertFalse(HttpResponseCache.invalidate(CacheKey("nope")))
    }

    @Test
    fun `invalidateByPrefix removes matching entries only`() {
        HttpResponseCache.put(CacheKey("GET|https://j/issue/PROJ-1|auth=x|accept=json"), entry())
        HttpResponseCache.put(CacheKey("GET|https://j/issue/PROJ-1/transitions|auth=x|accept=json"), entry())
        HttpResponseCache.put(CacheKey("GET|https://j/issue/OTHER-2|auth=x|accept=json"), entry())

        val removed = HttpResponseCache.invalidateByPrefix("/issue/PROJ-1")
        assertEquals(2, removed)
        assertNull(HttpResponseCache.get(CacheKey("GET|https://j/issue/PROJ-1|auth=x|accept=json")))
        assertNotNull(HttpResponseCache.get(CacheKey("GET|https://j/issue/OTHER-2|auth=x|accept=json")))
    }

    @Test
    fun `invalidateAll empties cache and zeros byte metrics`() {
        HttpResponseCache.put(CacheKey("a"), entry(tag = "jira", size = 100))
        HttpResponseCache.put(CacheKey("b"), entry(tag = "bamboo", size = 200))

        HttpResponseCache.invalidateAll()
        assertEquals(0L, HttpResponseCache.estimatedSize())
        assertEquals(0L, HttpCacheMetrics.getStats("jira").bytesInCache)
        assertEquals(0L, HttpCacheMetrics.getStats("bamboo").bytesInCache)
    }

    @Test
    fun `entry isFresh respects TTL`() {
        val e = entry(ttl = 1)
        assertTrue(e.isFresh)

        // Construct a "stale" entry by backdating storedAtMillis
        val stale = HttpResponseCache.Entry(
            bodyBytes = ByteArray(10),
            sha256 = ByteArray(32),
            contentType = null,
            statusCode = 200,
            tag = "jira",
            storedAtMillis = System.currentTimeMillis() - 5000L,
            ttlSeconds = 1
        )
        assertFalse(stale.isFresh)
    }

    @Test
    fun `CacheKey includes method URL auth and accept`() {
        val getReq = Request.Builder()
            .url("https://jira/rest/api/2/issue/X")
            .header("Authorization", "Bearer token1")
            .header("Accept", "application/json")
            .get()
            .build()
        val postReq = Request.Builder()
            .url("https://jira/rest/api/2/issue/X")
            .header("Authorization", "Bearer token1")
            .header("Accept", "application/json")
            .post(ByteArray(0).toRequestBody(null))
            .build()

        assertNotEquals(CacheKey.of(getReq), CacheKey.of(postReq))
    }

    @Test
    fun `CacheKey differs across users (Authorization)`() {
        val user1 = Request.Builder()
            .url("https://jira/rest/api/2/issue/X")
            .header("Authorization", "Bearer abcd")
            .get()
            .build()
        val user2 = Request.Builder()
            .url("https://jira/rest/api/2/issue/X")
            .header("Authorization", "Bearer xyzw")
            .get()
            .build()

        assertNotEquals(CacheKey.of(user1), CacheKey.of(user2))
    }

    @Test
    fun `CacheKey does not contain raw Authorization value`() {
        val req = Request.Builder()
            .url("https://jira/rest/api/2/issue/X")
            .header("Authorization", "Bearer SuperSecretToken123")
            .get()
            .build()
        val key = CacheKey.of(req).value
        assertFalse(key.contains("SuperSecretToken123"), "key leaked raw Authorization: $key")
    }

    @Test
    fun `CacheKey with no Authorization header normalises to anon`() {
        val req = Request.Builder()
            .url("https://jira/rest/api/2/issue/X")
            .get()
            .build()
        val key = CacheKey.of(req).value
        assertTrue(key.contains("auth=anon"))
    }
}

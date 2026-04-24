package com.workflow.orchestrator.core.http

import com.workflow.orchestrator.core.model.ServiceType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class CachingInterceptorTest {

    private lateinit var server: MockWebServer
    private lateinit var jiraClient: OkHttpClient

    @BeforeEach
    fun setUp() {
        HttpCacheMetrics.reset()
        HttpResponseCache.clearForTest()
        server = MockWebServer()
        server.start()
        jiraClient = OkHttpClient.Builder()
            .addInterceptor(CachingInterceptor(ServiceType.JIRA))
            .build()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
        HttpCacheMetrics.reset()
        HttpResponseCache.clearForTest()
    }

    private fun get(path: String, auth: String = "Bearer t"): okhttp3.Response {
        val url = server.url(path)
        val req = Request.Builder().url(url).header("Authorization", auth).get().build()
        return jiraClient.newCall(req).execute()
    }

    @Test
    fun `cacheable URL - first call misses and populates store`() {
        server.enqueue(MockResponse().setBody("""{"id":1}""").setHeader("Content-Type", "application/json").setResponseCode(200))

        val body = get("/rest/agile/1.0/board").use { it.body!!.string() }
        assertEquals("""{"id":1}""", body)

        val stats = HttpCacheMetrics.getStats("jira")
        assertEquals(1L, stats.miss)
        assertEquals(0L, stats.hitFresh)
        assertEquals(1L, stats.entriesInCache)
    }

    @Test
    fun `cacheable URL - second call serves from cache`() {
        server.enqueue(MockResponse().setBody("""{"id":1}""").setHeader("Content-Type", "application/json"))

        val body1 = get("/rest/agile/1.0/board").use { it.body!!.string() }
        val response2 = get("/rest/agile/1.0/board")
        val body2 = response2.use { it.body!!.string() }

        assertEquals("""{"id":1}""", body1)
        assertEquals("""{"id":1}""", body2)
        assertEquals("HIT", response2.header("X-Cache"))
        assertEquals(1, server.requestCount, "second call must not hit the network")

        val stats = HttpCacheMetrics.getStats("jira")
        assertEquals(1L, stats.miss)
        assertEquals(1L, stats.hitFresh)
    }

    @Test
    fun `sensitive path is never cached`() {
        server.enqueue(MockResponse().setBody("""{"name":"me"}""").setHeader("Content-Type", "application/json"))
        server.enqueue(MockResponse().setBody("""{"name":"me"}""").setHeader("Content-Type", "application/json"))

        get("/rest/api/2/myself").use { it.body!!.string() }
        get("/rest/api/2/myself").use { it.body!!.string() }

        assertEquals(2, server.requestCount, "both calls must hit the network")
        val stats = HttpCacheMetrics.getStats("jira")
        assertEquals(2L, stats.miss)
        assertEquals(0L, stats.hitFresh)
        assertNull(HttpResponseCache.get(CacheKey.of(
            Request.Builder().url(server.url("/rest/api/2/myself")).header("Authorization", "Bearer t").get().build()
        )))
    }

    @Test
    fun `unknown-pattern URL is not cached (conservative default)`() {
        server.enqueue(MockResponse().setBody("{}"))
        server.enqueue(MockResponse().setBody("{}"))

        get("/rest/api/2/some-weird-endpoint").use { it.body!!.string() }
        get("/rest/api/2/some-weird-endpoint").use { it.body!!.string() }

        assertEquals(2, server.requestCount)
        assertEquals(2L, HttpCacheMetrics.getStats("jira").miss)
        assertEquals(0L, HttpCacheMetrics.getStats("jira").hitFresh)
    }

    @Test
    fun `non-200 response is not cached`() {
        server.enqueue(MockResponse().setResponseCode(404).setBody("not found"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        get("/rest/agile/1.0/board").close()
        get("/rest/agile/1.0/board").close()

        // Both calls miss (first because 404 not cached, second because first didn't populate)
        assertEquals(2, server.requestCount)
        assertEquals(2L, HttpCacheMetrics.getStats("jira").miss)
    }

    @Test
    fun `POST is never counted or cached`() {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":7}"""))

        val req = Request.Builder()
            .url(server.url("/rest/api/2/issue"))
            .header("Authorization", "Bearer t")
            .post("{}".toRequestBody("application/json".toMediaType()))
            .build()
        jiraClient.newCall(req).execute().close()

        val stats = HttpCacheMetrics.getStats("jira")
        assertEquals(0L, stats.total)
    }

    @Test
    fun `different Authorization values do not collide`() {
        server.enqueue(MockResponse().setBody("""{"user":"alice"}""").setHeader("Content-Type", "application/json"))
        server.enqueue(MockResponse().setBody("""{"user":"bob"}""").setHeader("Content-Type", "application/json"))

        val a = get("/rest/agile/1.0/board", auth = "Bearer tokenA").use { it.body!!.string() }
        val b = get("/rest/agile/1.0/board", auth = "Bearer tokenB").use { it.body!!.string() }

        assertEquals("""{"user":"alice"}""", a)
        assertEquals("""{"user":"bob"}""", b)
        assertEquals(2, server.requestCount, "different auth ⇒ different cache keys ⇒ two network calls")
        assertEquals(2L, HttpCacheMetrics.getStats("jira").miss)
    }

    @Test
    fun `stale entry, new bytes differ - HIT_STALE_DIFFER`() {
        val req = Request.Builder().url(server.url("/rest/agile/1.0/board")).header("Authorization", "Bearer t").get().build()

        // Prime with a stale entry whose sha256 will NOT match the upcoming response.
        val staleBody = """{"stale":true}""".toByteArray()
        HttpResponseCache.put(
            CacheKey.of(req),
            HttpResponseCache.Entry(
                bodyBytes = staleBody,
                sha256 = java.security.MessageDigest.getInstance("SHA-256").digest(staleBody),
                contentType = "application/json",
                statusCode = 200,
                tag = "jira",
                storedAtMillis = System.currentTimeMillis() - 10 * 60 * 1000L,
                ttlSeconds = 300L
            )
        )
        server.enqueue(MockResponse().setBody("""{"v":1}""").setHeader("Content-Type", "application/json"))

        val body = jiraClient.newCall(req).execute().use { it.body!!.string() }
        assertEquals("""{"v":1}""", body)
        val stats = HttpCacheMetrics.getStats("jira")
        assertEquals(1L, stats.hitStaleDiffer)
        assertEquals(0L, stats.hitStaleMatch)
        assertEquals(0L, stats.miss, "stale-but-present should not be a MISS — cached entry exists")
    }

    @Test
    fun `stale entry, new bytes identical - HIT_STALE_MATCH`() {
        val req = Request.Builder().url(server.url("/rest/agile/1.0/board")).header("Authorization", "Bearer t").get().build()
        val identicalBody = """{"v":1}"""

        HttpResponseCache.put(
            CacheKey.of(req),
            HttpResponseCache.Entry(
                bodyBytes = identicalBody.toByteArray(),
                sha256 = java.security.MessageDigest.getInstance("SHA-256").digest(identicalBody.toByteArray()),
                contentType = "application/json",
                statusCode = 200,
                tag = "jira",
                storedAtMillis = System.currentTimeMillis() - 10 * 60 * 1000L,
                ttlSeconds = 300L
            )
        )
        server.enqueue(MockResponse().setBody(identicalBody).setHeader("Content-Type", "application/json"))

        val body = jiraClient.newCall(req).execute().use { it.body!!.string() }
        assertEquals(identicalBody, body)
        val stats = HttpCacheMetrics.getStats("jira")
        assertEquals(1L, stats.hitStaleMatch)
        assertEquals(0L, stats.hitStaleDiffer)
        assertEquals(0L, stats.miss)
    }

    @Test
    fun `stale-match refreshes the storedAt timestamp so next call is HIT_FRESH`() {
        val req = Request.Builder().url(server.url("/rest/agile/1.0/board")).header("Authorization", "Bearer t").get().build()
        val bodyStr = """{"v":1}"""

        // Prime stale
        HttpResponseCache.put(
            CacheKey.of(req),
            HttpResponseCache.Entry(
                bodyBytes = bodyStr.toByteArray(),
                sha256 = java.security.MessageDigest.getInstance("SHA-256").digest(bodyStr.toByteArray()),
                contentType = "application/json",
                statusCode = 200,
                tag = "jira",
                storedAtMillis = System.currentTimeMillis() - 10 * 60 * 1000L,
                ttlSeconds = 300L
            )
        )
        server.enqueue(MockResponse().setBody(bodyStr).setHeader("Content-Type", "application/json"))

        // First call triggers HIT_STALE_MATCH and refreshes timestamp
        jiraClient.newCall(req).execute().close()

        // Second call should now be HIT_FRESH (no network enqueue needed)
        jiraClient.newCall(req).execute().close()

        assertEquals(1, server.requestCount, "second call must not hit the network")
        val stats = HttpCacheMetrics.getStats("jira")
        assertEquals(1L, stats.hitStaleMatch)
        assertEquals(1L, stats.hitFresh)
    }

    @Test
    fun `body content-type preserved across cached roundtrip`() {
        server.enqueue(MockResponse().setBody("<xml/>").setHeader("Content-Type", "application/xml"))

        get("/rest/agile/1.0/board").use { it.body!!.string() }
        val mediaType = get("/rest/agile/1.0/board").use { it.body!!.contentType() }
        assertNotNull(mediaType)
        assertEquals("xml", mediaType!!.subtype)
    }

    @Test
    fun `per-service interceptor tags metrics correctly`() {
        val bambooClient = OkHttpClient.Builder().addInterceptor(CachingInterceptor(ServiceType.BAMBOO)).build()
        server.enqueue(MockResponse().setBody("""{"key":"BLD-1"}""").setHeader("Content-Type", "application/json"))

        val req = Request.Builder().url(server.url("/rest/api/latest/plan")).header("Authorization", "Bearer t").get().build()
        bambooClient.newCall(req).execute().close()

        assertEquals(1L, HttpCacheMetrics.getStats("bamboo").miss)
        assertEquals(0L, HttpCacheMetrics.getStats("jira").miss)
    }

    @Test
    fun `three consecutive fresh reads - one miss then two hits`() {
        server.enqueue(MockResponse().setBody("""{"x":1}""").setHeader("Content-Type", "application/json"))
        repeat(3) {
            get("/rest/agile/1.0/board").use { it.body!!.string() }
        }
        assertEquals(1, server.requestCount)
        val stats = HttpCacheMetrics.getStats("jira")
        assertEquals(1L, stats.miss)
        assertEquals(2L, stats.hitFresh)
    }
}

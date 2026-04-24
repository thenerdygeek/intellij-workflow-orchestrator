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

class MutationInvalidationInterceptorTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    @BeforeEach
    fun setUp() {
        HttpCacheMetrics.reset()
        HttpResponseCache.clearForTest()
        server = MockWebServer()
        server.start()
        client = OkHttpClient.Builder()
            .addInterceptor(CachingInterceptor(ServiceType.JIRA))
            .addInterceptor(MutationInvalidationInterceptor(ServiceType.JIRA))
            .build()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
        HttpCacheMetrics.reset()
        HttpResponseCache.clearForTest()
    }

    private fun primeCache(path: String, body: String) {
        server.enqueue(MockResponse().setBody(body).setHeader("Content-Type", "application/json"))
        client.newCall(
            Request.Builder().url(server.url(path)).header("Authorization", "Bearer t").get().build()
        ).execute().close()
    }

    private fun mutate(method: String, path: String): okhttp3.Response {
        val req = Request.Builder()
            .url(server.url(path))
            .header("Authorization", "Bearer t")
            .method(method, "{}".toRequestBody("application/json".toMediaType()))
            .build()
        return client.newCall(req).execute()
    }

    @Test
    fun `POST on jira transitions invalidates cached issue GET`() {
        primeCache("/rest/api/2/issue/PROJ-1", """{"status":"To Do"}""")
        assertEquals(1L, HttpCacheMetrics.getStats("jira").entriesInCache)

        server.enqueue(MockResponse().setResponseCode(204))
        mutate("POST", "/rest/api/2/issue/PROJ-1/transitions").close()

        assertEquals(0L, HttpCacheMetrics.getStats("jira").entriesInCache)
        assertEquals(1L, HttpCacheMetrics.getStats("jira").invalidatedByMutation)
    }

    @Test
    fun `POST on jira comments invalidates cached issue GET`() {
        primeCache("/rest/api/2/issue/PROJ-1", """{"x":1}""")

        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":99}"""))
        mutate("POST", "/rest/api/2/issue/PROJ-1/comment").close()

        assertEquals(0L, HttpCacheMetrics.getStats("jira").entriesInCache)
    }

    @Test
    fun `DELETE on jira sub-resource invalidates parent issue GET`() {
        primeCache("/rest/api/2/issue/PROJ-1", """{"x":1}""")

        server.enqueue(MockResponse().setResponseCode(204))
        mutate("DELETE", "/rest/api/2/issue/PROJ-1/comment/42").close()

        assertEquals(0L, HttpCacheMetrics.getStats("jira").entriesInCache)
    }

    @Test
    fun `PUT on jira issue invalidates cache`() {
        primeCache("/rest/api/2/issue/PROJ-1", """{"x":1}""")

        server.enqueue(MockResponse().setResponseCode(204))
        mutate("PUT", "/rest/api/2/issue/PROJ-1").close()

        assertEquals(0L, HttpCacheMetrics.getStats("jira").entriesInCache)
    }

    @Test
    fun `mutation on different issue does not affect other cached entries`() {
        primeCache("/rest/api/2/issue/PROJ-1", """{"x":1}""")
        primeCache("/rest/api/2/issue/PROJ-2", """{"y":2}""")
        assertEquals(2L, HttpCacheMetrics.getStats("jira").entriesInCache)

        server.enqueue(MockResponse().setResponseCode(204))
        mutate("POST", "/rest/api/2/issue/PROJ-1/transitions").close()

        assertEquals(1L, HttpCacheMetrics.getStats("jira").entriesInCache)
        assertNull(HttpResponseCache.get(keyFor("/rest/api/2/issue/PROJ-1")))
        assertNotNull(HttpResponseCache.get(keyFor("/rest/api/2/issue/PROJ-2")))
    }

    @Test
    fun `non-2xx mutation does not invalidate cache`() {
        primeCache("/rest/api/2/issue/PROJ-1", """{"x":1}""")

        server.enqueue(MockResponse().setResponseCode(403).setBody("forbidden"))
        mutate("POST", "/rest/api/2/issue/PROJ-1/transitions").close()

        assertEquals(1L, HttpCacheMetrics.getStats("jira").entriesInCache,
            "cache must survive a failed mutation — state didn't actually change")
        assertEquals(0L, HttpCacheMetrics.getStats("jira").invalidatedByMutation)
    }

    @Test
    fun `GET never triggers invalidation`() {
        primeCache("/rest/api/2/issue/PROJ-1", """{"x":1}""")
        assertEquals(1L, HttpCacheMetrics.getStats("jira").entriesInCache)

        // Another GET on a sub-path — reaches a policy-matched pattern, so it IS cached
        // (a second entry is added) — but what we're asserting is that the GET did NOT
        // fire mutation invalidation on the existing PROJ-1 entry.
        server.enqueue(MockResponse().setBody("""{"transitions":[]}""").setHeader("Content-Type", "application/json"))
        client.newCall(
            Request.Builder().url(server.url("/rest/api/2/issue/PROJ-1/transitions"))
                .header("Authorization", "Bearer t").get().build()
        ).execute().close()

        assertEquals(0L, HttpCacheMetrics.getStats("jira").invalidatedByMutation,
            "GET must never fire mutation invalidation — reads don't invalidate")
        assertNotNull(HttpResponseCache.get(keyFor("/rest/api/2/issue/PROJ-1")),
            "PROJ-1 GET entry must still be present after a sibling GET")
    }

    @Test
    fun `mutation on unmapped service does nothing`() {
        val sonarClient = OkHttpClient.Builder()
            .addInterceptor(CachingInterceptor(ServiceType.SONARQUBE))
            .addInterceptor(MutationInvalidationInterceptor(ServiceType.SONARQUBE))
            .build()

        server.enqueue(MockResponse().setResponseCode(200))
        val req = Request.Builder()
            .url(server.url("/api/something/write"))
            .header("Authorization", "Bearer t")
            .post("{}".toRequestBody("application/json".toMediaType()))
            .build()
        sonarClient.newCall(req).execute().close()

        assertEquals(0L, HttpCacheMetrics.getStats("sonar").invalidatedByMutation)
    }

    @Test
    fun `bitbucket PR mutation invalidates that PR's cached reads`() {
        val bbClient = OkHttpClient.Builder()
            .addInterceptor(CachingInterceptor(ServiceType.BITBUCKET))
            .addInterceptor(MutationInvalidationInterceptor(ServiceType.BITBUCKET))
            .build()
        // Prime a PR GET
        server.enqueue(MockResponse().setBody("""{"id":7}""").setHeader("Content-Type", "application/json"))
        bbClient.newCall(
            Request.Builder().url(server.url("/rest/api/1.0/projects/P/repos/R/pull-requests/7"))
                .header("Authorization", "Bearer t").get().build()
        ).execute().close()
        assertEquals(1L, HttpCacheMetrics.getStats("bitbucket").entriesInCache)

        // Approve the PR
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))
        val approveReq = Request.Builder()
            .url(server.url("/rest/api/1.0/projects/P/repos/R/pull-requests/7/approve"))
            .header("Authorization", "Bearer t")
            .post("{}".toRequestBody("application/json".toMediaType()))
            .build()
        bbClient.newCall(approveReq).execute().close()

        assertEquals(0L, HttpCacheMetrics.getStats("bitbucket").entriesInCache)
        assertEquals(1L, HttpCacheMetrics.getStats("bitbucket").invalidatedByMutation)
    }

    @Test
    fun `mutation invalidation counter sums per evicted entry`() {
        // Multiple cached entries sharing the same issue prefix
        primeCache("/rest/api/2/issue/PROJ-1", """{"x":1}""")

        // Manually add a sibling cached entry that matches the same prefix
        val siblingReq = Request.Builder()
            .url(server.url("/rest/api/2/issue/PROJ-1/transitions"))
            .header("Authorization", "Bearer t")
            .get()
            .build()
        HttpResponseCache.put(
            CacheKey.of(siblingReq),
            HttpResponseCache.Entry(
                bodyBytes = """{"transitions":[]}""".toByteArray(),
                sha256 = ByteArray(32),
                contentType = "application/json",
                statusCode = 200,
                tag = "jira",
                storedAtMillis = System.currentTimeMillis(),
                ttlSeconds = 60L
            )
        )
        assertEquals(2L, HttpCacheMetrics.getStats("jira").entriesInCache)

        server.enqueue(MockResponse().setResponseCode(204))
        mutate("POST", "/rest/api/2/issue/PROJ-1/transitions").close()

        assertEquals(0L, HttpCacheMetrics.getStats("jira").entriesInCache)
        assertEquals(2L, HttpCacheMetrics.getStats("jira").invalidatedByMutation)
    }

    private fun keyFor(path: String): CacheKey = CacheKey.of(
        Request.Builder().url(server.url(path)).header("Authorization", "Bearer t").get().build()
    )
}

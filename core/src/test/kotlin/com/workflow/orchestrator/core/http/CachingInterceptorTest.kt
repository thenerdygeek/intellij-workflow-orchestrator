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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class CachingInterceptorTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    @BeforeEach
    fun setUp() {
        HttpCacheMetrics.reset()
        server = MockWebServer()
        server.start()
        client = OkHttpClient.Builder()
            .addInterceptor(CachingInterceptor(ServiceType.JIRA))
            .build()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
        HttpCacheMetrics.reset()
    }

    @Test
    fun `GET request counted as miss in pass-through mode`() {
        server.enqueue(MockResponse().setBody("{}").setResponseCode(200))

        client.newCall(Request.Builder().url(server.url("/rest/api/2/issue/X")).get().build()).execute().close()

        val stats = HttpCacheMetrics.getStats("jira")
        assertEquals(1L, stats.miss)
        assertEquals(0L, stats.hitFresh)
        assertEquals(0L, stats.hitStaleMatch)
        assertEquals(1L, stats.total)
    }

    @Test
    fun `POST request is not counted`() {
        server.enqueue(MockResponse().setBody("{}").setResponseCode(201))

        val body = "{}".toRequestBody("application/json".toMediaType())
        client.newCall(Request.Builder().url(server.url("/rest/api/2/issue")).post(body).build()).execute().close()

        val stats = HttpCacheMetrics.getStats("jira")
        assertEquals(0L, stats.miss)
        assertEquals(0L, stats.total)
    }

    @Test
    fun `PUT request is not counted`() {
        server.enqueue(MockResponse().setBody("{}").setResponseCode(200))

        val body = "{}".toRequestBody("application/json".toMediaType())
        client.newCall(Request.Builder().url(server.url("/rest/api/2/issue/X")).put(body).build()).execute().close()

        val stats = HttpCacheMetrics.getStats("jira")
        assertEquals(0L, stats.total)
    }

    @Test
    fun `DELETE request is not counted`() {
        server.enqueue(MockResponse().setResponseCode(204))

        client.newCall(Request.Builder().url(server.url("/rest/api/2/issue/X")).delete().build()).execute().close()

        val stats = HttpCacheMetrics.getStats("jira")
        assertEquals(0L, stats.total)
    }

    @Test
    fun `service tag matches ServiceType`() {
        val bambooClient = OkHttpClient.Builder()
            .addInterceptor(CachingInterceptor(ServiceType.BAMBOO))
            .build()
        server.enqueue(MockResponse().setBody("{}"))
        bambooClient.newCall(Request.Builder().url(server.url("/rest/api/latest/plan")).get().build()).execute().close()

        assertEquals(0L, HttpCacheMetrics.getStats("jira").miss)
        assertEquals(1L, HttpCacheMetrics.getStats("bamboo").miss)
    }

    @Test
    fun `three consecutive GETs produce three misses in pass-through mode`() {
        repeat(3) { server.enqueue(MockResponse().setBody("{}")) }

        repeat(3) {
            client.newCall(Request.Builder().url(server.url("/rest/api/2/issue/X")).get().build()).execute().close()
        }

        val stats = HttpCacheMetrics.getStats("jira")
        assertEquals(3L, stats.miss)
        assertEquals(0.0, stats.hitRatePct)
    }
}

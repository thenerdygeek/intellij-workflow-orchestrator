package com.workflow.orchestrator.core.http

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Verifies RetryInterceptor only retries idempotent methods (GET/HEAD/OPTIONS).
 *
 * Retrying POST/PUT/DELETE/PATCH on a 5xx can duplicate side effects (Bamboo
 * builds, PR merges, Jira comments) because the server may have processed the
 * request before the failure surfaced.
 *
 * Closes audit finding core:F-1.
 */
class RetryInterceptorMethodPolicyTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        // Tiny delays so the retry path doesn't slow the suite.
        client = OkHttpClient.Builder()
            .addInterceptor(RetryInterceptor(maxRetries = 3, baseDelayMs = 1, maxDelayMs = 5))
            .build()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun url() = server.url("/").toString()

    @Test
    fun `GET retries on 503 then succeeds`() {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

        val resp = client.newCall(Request.Builder().url(url()).get().build()).execute()
        resp.use {
            assertEquals(200, it.code)
        }
        // Two requests reached the server: original + one retry.
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `HEAD retries on 503`() {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(200))

        val resp = client.newCall(Request.Builder().url(url()).head().build()).execute()
        resp.use { assertEquals(200, it.code) }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `POST does NOT retry on 503`() {
        server.enqueue(MockResponse().setResponseCode(503))
        // A second response is enqueued but must never be consumed.
        server.enqueue(MockResponse().setResponseCode(200).setBody("should-not-reach"))

        val body = "{}".toRequestBody()
        val resp = client.newCall(Request.Builder().url(url()).post(body).build()).execute()
        resp.use {
            assertEquals(503, it.code) // first response returned verbatim
        }
        // Only ONE request reached the server — no retry.
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `PUT does NOT retry on 503`() {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(200))

        val resp = client.newCall(Request.Builder().url(url()).put("{}".toRequestBody()).build()).execute()
        resp.use { assertEquals(503, it.code) }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `DELETE does NOT retry on 503`() {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(200))

        val resp = client.newCall(Request.Builder().url(url()).delete().build()).execute()
        resp.use { assertEquals(503, it.code) }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `PATCH does NOT retry on 503`() {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(200))

        val resp = client.newCall(
            Request.Builder().url(url()).patch("{}".toRequestBody()).build()
        ).execute()
        resp.use { assertEquals(503, it.code) }
        assertEquals(1, server.requestCount)
    }
}

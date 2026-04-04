package com.workflow.orchestrator.core.http

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import com.intellij.testFramework.LoggedErrorProcessorEnabler

@ExtendWith(LoggedErrorProcessorEnabler.DoNoRethrowErrors::class)
class RetryInterceptorTest {

    private lateinit var server: MockWebServer

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `retries on 503 and succeeds`() {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setBody("ok").setResponseCode(200))

        val client = OkHttpClient.Builder()
            .addInterceptor(RetryInterceptor(maxRetries = 3, baseDelayMs = 10))
            .build()

        val response = client.newCall(
            Request.Builder().url(server.url("/test")).build()
        ).execute()

        assertEquals(200, response.code)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `retries on 429 and succeeds`() {
        server.enqueue(MockResponse().setResponseCode(429))
        server.enqueue(MockResponse().setBody("ok").setResponseCode(200))

        val client = OkHttpClient.Builder()
            .addInterceptor(RetryInterceptor(maxRetries = 3, baseDelayMs = 10))
            .build()

        val response = client.newCall(
            Request.Builder().url(server.url("/test")).build()
        ).execute()

        assertEquals(200, response.code)
    }

    @Test
    fun `does not retry on 401`() {
        server.enqueue(MockResponse().setResponseCode(401))

        val client = OkHttpClient.Builder()
            .addInterceptor(RetryInterceptor(maxRetries = 3, baseDelayMs = 10))
            .build()

        val response = client.newCall(
            Request.Builder().url(server.url("/test")).build()
        ).execute()

        assertEquals(401, response.code)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `gives up after max retries`() {
        repeat(4) { server.enqueue(MockResponse().setResponseCode(500)) }

        val client = OkHttpClient.Builder()
            .addInterceptor(RetryInterceptor(maxRetries = 3, baseDelayMs = 10))
            .build()

        val response = client.newCall(
            Request.Builder().url(server.url("/test")).build()
        ).execute()

        assertEquals(500, response.code)
        assertEquals(4, server.requestCount) // 1 original + 3 retries
    }

    // ── Rate-limit header parsing tests (ported from Cline's retry.ts) ──

    @Test
    fun `uses retry-after header for 429 delay`() {
        // Server sends 429 with retry-after: 1 (1 second), then succeeds
        server.enqueue(MockResponse()
            .setResponseCode(429)
            .setHeader("retry-after", "1"))
        server.enqueue(MockResponse().setBody("ok").setResponseCode(200))

        val client = OkHttpClient.Builder()
            .addInterceptor(RetryInterceptor(maxRetries = 3, baseDelayMs = 10))
            .build()

        val response = client.newCall(
            Request.Builder().url(server.url("/test")).build()
        ).execute()

        assertEquals(200, response.code)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `uses x-ratelimit-reset header when retry-after is absent`() {
        server.enqueue(MockResponse()
            .setResponseCode(429)
            .setHeader("x-ratelimit-reset", "1"))
        server.enqueue(MockResponse().setBody("ok").setResponseCode(200))

        val client = OkHttpClient.Builder()
            .addInterceptor(RetryInterceptor(maxRetries = 3, baseDelayMs = 10))
            .build()

        val response = client.newCall(
            Request.Builder().url(server.url("/test")).build()
        ).execute()

        assertEquals(200, response.code)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `uses ratelimit-reset header as fallback`() {
        server.enqueue(MockResponse()
            .setResponseCode(429)
            .setHeader("ratelimit-reset", "1"))
        server.enqueue(MockResponse().setBody("ok").setResponseCode(200))

        val client = OkHttpClient.Builder()
            .addInterceptor(RetryInterceptor(maxRetries = 3, baseDelayMs = 10))
            .build()

        val response = client.newCall(
            Request.Builder().url(server.url("/test")).build()
        ).execute()

        assertEquals(200, response.code)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `parseRetryAfterHeaders returns null when no headers present`() {
        val interceptor = RetryInterceptor()
        server.enqueue(MockResponse().setResponseCode(429))

        val client = OkHttpClient.Builder().build()
        val response = client.newCall(
            Request.Builder().url(server.url("/test")).build()
        ).execute()

        val delay = interceptor.parseRetryAfterHeaders(response)
        assertNull(delay)
    }

    @Test
    fun `parseRetryAfterHeaders handles delta-seconds format`() {
        val interceptor = RetryInterceptor()
        server.enqueue(MockResponse()
            .setResponseCode(429)
            .setHeader("retry-after", "30"))

        val client = OkHttpClient.Builder().build()
        val response = client.newCall(
            Request.Builder().url(server.url("/test")).build()
        ).execute()

        val delay = interceptor.parseRetryAfterHeaders(response)
        assertNotNull(delay)
        assertEquals(30_000L, delay, "30 seconds should be 30000ms")
    }

    @Test
    fun `parseRetryAfterHeaders handles Unix timestamp format`() {
        val interceptor = RetryInterceptor()
        // Set a timestamp 60 seconds in the future
        val futureTimestamp = (System.currentTimeMillis() / 1000) + 60
        server.enqueue(MockResponse()
            .setResponseCode(429)
            .setHeader("retry-after", futureTimestamp.toString()))

        val client = OkHttpClient.Builder().build()
        val response = client.newCall(
            Request.Builder().url(server.url("/test")).build()
        ).execute()

        val delay = interceptor.parseRetryAfterHeaders(response)
        assertNotNull(delay)
        // Should be approximately 60 seconds (allow some tolerance for test execution time)
        assertTrue(delay!! in 55_000L..65_000L, "Expected ~60s delay but got ${delay}ms")
    }

    @Test
    fun `parseRetryAfterHeaders ignores non-numeric values`() {
        val interceptor = RetryInterceptor()
        server.enqueue(MockResponse()
            .setResponseCode(429)
            .setHeader("retry-after", "Thu, 01 Dec 2025 16:00:00 GMT"))

        val client = OkHttpClient.Builder().build()
        val response = client.newCall(
            Request.Builder().url(server.url("/test")).build()
        ).execute()

        val delay = interceptor.parseRetryAfterHeaders(response)
        assertNull(delay, "Non-numeric header should return null")
    }

    @Test
    fun `falls back to exponential backoff when no retry-after header`() {
        // 3 failures with no retry-after header, then success
        server.enqueue(MockResponse().setResponseCode(429))
        server.enqueue(MockResponse().setResponseCode(429))
        server.enqueue(MockResponse().setBody("ok").setResponseCode(200))

        val client = OkHttpClient.Builder()
            .addInterceptor(RetryInterceptor(maxRetries = 3, baseDelayMs = 10))
            .build()

        val response = client.newCall(
            Request.Builder().url(server.url("/test")).build()
        ).execute()

        assertEquals(200, response.code)
        assertEquals(3, server.requestCount) // original + 2 retries
    }
}

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
}

package com.workflow.orchestrator.core.http

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AuthInterceptorTest {

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
    fun `adds Bearer token to request`() {
        server.enqueue(MockResponse().setBody("ok"))

        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor({ "my-secret-token" }, AuthScheme.BEARER))
            .build()

        client.newCall(Request.Builder().url(server.url("/test")).build()).execute()

        val recorded = server.takeRequest()
        assertEquals("Bearer my-secret-token", recorded.getHeader("Authorization"))
    }

    @Test
    fun `adds Basic auth header for BASIC scheme with pre-encoded token`() {
        server.enqueue(MockResponse().setBody("ok"))

        // BASIC scheme expects pre-encoded base64(username:password) from the token provider
        val preEncoded = java.util.Base64.getEncoder().encodeToString("admin:secret123".toByteArray())
        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor({ preEncoded }, AuthScheme.BASIC))
            .build()

        client.newCall(Request.Builder().url(server.url("/test")).build()).execute()

        val recorded = server.takeRequest()
        assertEquals("Basic $preEncoded", recorded.getHeader("Authorization"))
    }

    @Test
    fun `skips auth header when token provider returns null`() {
        server.enqueue(MockResponse().setBody("ok"))

        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(tokenProvider = { null }))
            .build()

        client.newCall(Request.Builder().url(server.url("/test")).build()).execute()

        val recorded = server.takeRequest()
        assertNull(recorded.getHeader("Authorization"))
    }
}

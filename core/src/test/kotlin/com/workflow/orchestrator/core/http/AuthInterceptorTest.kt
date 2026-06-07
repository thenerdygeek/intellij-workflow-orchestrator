package com.workflow.orchestrator.core.http

import com.workflow.orchestrator.core.auth.Credential
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
    fun `skips auth header when token provider returns null`() {
        server.enqueue(MockResponse().setBody("ok"))

        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(tokenProvider = { null }))
            .build()

        client.newCall(Request.Builder().url(server.url("/test")).build()).execute()

        val recorded = server.takeRequest()
        assertNull(recorded.getHeader("Authorization"))
    }

    @Test
    fun `fromCredential applies a Basic credential`() {
        server.enqueue(MockResponse().setBody("ok"))

        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor.fromCredential { Credential.Basic("user", "pass") })
            .build()

        client.newCall(Request.Builder().url(server.url("/test")).build()).execute()

        val recorded = server.takeRequest()
        // base64("user:pass") == dXNlcjpwYXNz
        assertEquals("Basic dXNlcjpwYXNz", recorded.getHeader("Authorization"))
    }

    @Test
    fun `fromCredential skips header when credential is null`() {
        server.enqueue(MockResponse().setBody("ok"))

        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor.fromCredential { null })
            .build()

        client.newCall(Request.Builder().url(server.url("/test")).build()).execute()

        val recorded = server.takeRequest()
        assertNull(recorded.getHeader("Authorization"))
    }
}

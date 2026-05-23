// Copyright 2025-2026 Workflow Orchestrator Contributors
// SPDX-License-Identifier: Apache-2.0
package com.workflow.orchestrator.web.service

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Verifies [StripAuthHeadersInterceptor] behaviour using [MockWebServer] + [OkHttpClient].
 *
 * Each test issues a real HTTP request (to MockWebServer) through a client that has
 * ONLY the [StripAuthHeadersInterceptor] added. The test then inspects the recorded
 * request to confirm which headers reached the server.
 */
class StripAuthHeadersInterceptorTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    @BeforeEach
    fun setUp() {
        server = MockWebServer().apply { start() }
        client = OkHttpClient.Builder()
            .addInterceptor(StripAuthHeadersInterceptor())
            .build()
        // Enqueue an empty 200 for every request in each test
        repeat(10) { server.enqueue(MockResponse().setResponseCode(200)) }
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun get(vararg headers: Pair<String, String>): okhttp3.mockwebserver.RecordedRequest {
        val builder = Request.Builder().url(server.url("/"))
        headers.forEach { (k, v) -> builder.header(k, v) }
        client.newCall(builder.build()).execute().close()
        return server.takeRequest()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Stripped headers
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `Authorization header is stripped`() {
        val req = get("Authorization" to "Bearer abc123")
        assertNull(
            req.getHeader("Authorization"),
            "Authorization must be stripped but was present"
        )
    }

    @Test
    fun `all auth-related headers stripped simultaneously`() {
        val req = get(
            "Authorization" to "Bearer secret",
            "Cookie" to "session=xyz",
            "X-Api-Key" to "key-abc",
            "X-Subscription-Token" to "sub-tok",
            "Proxy-Authorization" to "Basic creds",
        )
        assertNull(req.getHeader("Authorization"), "Authorization must be stripped")
        assertNull(req.getHeader("Cookie"), "Cookie must be stripped")
        assertNull(req.getHeader("X-Api-Key"), "X-Api-Key must be stripped")
        assertNull(req.getHeader("X-Subscription-Token"), "X-Subscription-Token must be stripped")
        assertNull(req.getHeader("Proxy-Authorization"), "Proxy-Authorization must be stripped")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Non-stripped headers pass through
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `non-auth headers pass through unchanged`() {
        val req = get(
            "Accept" to "text/html",
            "User-Agent" to "TestBot/1.0",
            "X-Custom-Header" to "my-value",
        )
        assertEquals("text/html", req.getHeader("Accept"), "Accept must pass through")
        assertEquals("TestBot/1.0", req.getHeader("User-Agent"), "User-Agent must pass through")
        assertEquals("my-value", req.getHeader("X-Custom-Header"), "X-Custom-Header must pass through")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // X-Auth-Token (also in STRIPPED set)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `X-Auth-Token header is stripped`() {
        val req = get("X-Auth-Token" to "some-auth-token-value")
        assertNull(req.getHeader("X-Auth-Token"), "X-Auth-Token must be stripped")
    }
}

// Copyright 2025-2026 Workflow Orchestrator Contributors
// SPDX-License-Identifier: Apache-2.0
package com.workflow.orchestrator.web.service.search

import com.workflow.orchestrator.core.web.SearchProvider
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class TavilyProviderTest {

    private lateinit var server: MockWebServer
    private lateinit var sut: TavilyProvider

    @BeforeEach
    fun setUp() {
        server = MockWebServer().apply { start() }
        val baseUrl = server.url("/").toString().trimEnd('/')
        sut = TavilyProvider(baseUrl = baseUrl, apiKey = "tvly-test-key", client = OkHttpClient())
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `happy path returns one RawHit with correct fields`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "query": "kotlin coroutines launch vs async",
                      "results": [
                        {
                          "title": "Kotlin Coroutines Guide",
                          "url": "https://kotlinlang.org/docs/coroutines-guide.html",
                          "content": "Coroutines are light-weight threads",
                          "score": 0.93,
                          "raw_content": null
                        }
                      ],
                      "response_time": 1.42
                    }
                    """.trimIndent()
                )
        )

        val result = sut.search("kotlin coroutines launch vs async", 5)

        assertTrue(result.isSuccess, "Expected success but got: ${result.exceptionOrNull()?.message}")
        val hits = result.getOrThrow()
        assertEquals(1, hits.size)
        val hit = hits[0]
        assertEquals("Kotlin Coroutines Guide", hit.title)
        assertEquals("https://kotlinlang.org/docs/coroutines-guide.html", hit.url)
        assertEquals("Coroutines are light-weight threads", hit.snippet)
        assertEquals(0, hit.rank)

        // Verify the request was a POST with the correct body fields
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("tvly-test-key"), "Body should contain the API key")
        assertTrue(body.contains("kotlin coroutines launch vs async"), "Body should contain the query")
    }

    @Test
    fun `401 response returns failure with PROVIDER_AUTH_FAILED`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        val result = sut.search("query", 5)

        assertTrue(result.isFailure)
        assertTrue(
            result.exceptionOrNull()?.message?.contains("PROVIDER_AUTH_FAILED") == true,
            "Expected PROVIDER_AUTH_FAILED but got: ${result.exceptionOrNull()?.message}"
        )
    }

    @Test
    fun `malformed JSON body returns failure with PROVIDER_MALFORMED_RESPONSE`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("not valid json {{{{")
        )

        val result = sut.search("query", 5)

        assertTrue(result.isFailure)
        assertTrue(
            result.exceptionOrNull()?.message?.contains("PROVIDER_MALFORMED_RESPONSE") == true,
            "Expected PROVIDER_MALFORMED_RESPONSE but got: ${result.exceptionOrNull()?.message}"
        )
    }

    @Test
    fun `validate returns success when apiKey is non-blank`() = runTest {
        val result = sut.validate()
        assertTrue(result.isSuccess, "Expected validate to succeed with a non-blank apiKey")
    }

    @Test
    fun `validate returns failure when apiKey is null`() = runTest {
        val noKeyProvider = TavilyProvider(
            baseUrl = "https://api.tavily.com",
            apiKey = null,
            client = OkHttpClient()
        )
        val result = noKeyProvider.validate()
        assertTrue(result.isFailure, "Expected validate to fail with null apiKey")
    }

    @Test
    fun `validate returns failure when apiKey is blank`() = runTest {
        val blankKeyProvider = TavilyProvider(
            baseUrl = "https://api.tavily.com",
            apiKey = "   ",
            client = OkHttpClient()
        )
        val result = blankKeyProvider.validate()
        assertTrue(result.isFailure, "Expected validate to fail with blank apiKey")
    }

    @Test
    fun `provider id is TAVILY`() {
        assertEquals(SearchProvider.ProviderId.TAVILY, sut.id)
    }
}

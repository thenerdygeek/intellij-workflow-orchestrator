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
import java.time.Duration

class BraveProviderTest {

    private lateinit var server: MockWebServer
    private lateinit var sut: BraveProvider

    @BeforeEach
    fun setUp() {
        server = MockWebServer().apply { start() }
        val baseUrl = server.url("/").toString().trimEnd('/')
        sut = BraveProvider(baseUrl = baseUrl, apiKey = "test-api-key", client = OkHttpClient())
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
                    """{"web":{"results":[{"title":"Brave result","url":"https://example.com","description":"A description"}]}}"""
                )
        )

        val result = sut.search("brave search test", 5)

        assertTrue(result.isSuccess, "Expected success but got: ${result.exceptionOrNull()?.message}")
        val hits = result.getOrThrow()
        assertEquals(1, hits.size)
        with(hits[0]) {
            assertEquals("Brave result", title)
            assertEquals("https://example.com", url)
            assertEquals("A description", snippet)
            assertEquals(0, rank)
        }
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
                .setBody("{{{invalid json")
        )

        val result = sut.search("query", 5)

        assertTrue(result.isFailure)
        assertTrue(
            result.exceptionOrNull()?.message?.contains("PROVIDER_MALFORMED_RESPONSE") == true,
            "Expected PROVIDER_MALFORMED_RESPONSE but got: ${result.exceptionOrNull()?.message}"
        )
    }

    /**
     * I8 regression — search providers must size-cap the response body so a malicious or
     * misconfigured provider can't OOM the IDE with a 100 MB JSON blob.
     */
    @Test
    fun `provider response larger than 1MB is rejected with PROVIDER_RESPONSE_TOO_LARGE`() = runTest {
        // 2 MiB body — well over the 1 MiB cap baked into readBodyCapped.
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("x".repeat(2 * 1024 * 1024))
        )

        val result = sut.search("query", 5)

        assertTrue(result.isFailure, "Oversized body must fail, got: ${result.getOrNull()}")
        val msg = result.exceptionOrNull()?.message ?: ""
        assertTrue(
            msg.contains("PROVIDER_RESPONSE_TOO_LARGE"),
            "Expected PROVIDER_RESPONSE_TOO_LARGE but got: $msg"
        )
    }

    /**
     * Regression for B1: WebSearchServiceImpl previously installed StripAuthHeadersInterceptor
     * on the search OkHttpClient. That interceptor strips X-Subscription-Token, X-API-Key, and
     * Authorization — the exact headers Brave, CustomHttp, and similar providers use for auth.
     *
     * This test verifies that a client built WITHOUT the interceptor (matching the production
     * WebSearchServiceImpl after the R5 fix) forwards X-Subscription-Token to the server.
     * Plan rev R5: search providers authenticate via headers; auth-stripping must NOT apply to
     * the search client.
     */
    @Test
    fun `provider client without auth-stripping interceptor preserves X-Subscription-Token`() = runTest {
        // Use the same client construction as WebSearchServiceImpl (i.e. without auth-strip interceptor)
        val client = OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(10))
            .readTimeout(Duration.ofSeconds(30))
            // NO addInterceptor — matches production WebSearchServiceImpl after R5 fix
            .build()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"web":{"results":[{"title":"x","url":"https://x","description":"s"}]}}""")
        )
        val sut = BraveProvider(server.url("/").toString().trimEnd('/'), apiKey = "test-key", client = client)
        val result = sut.search("q", 5)
        assertTrue(result.isSuccess)
        val recorded = server.takeRequest()
        assertEquals("test-key", recorded.getHeader("X-Subscription-Token"))
    }
}

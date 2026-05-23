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
}

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

class SearXNGProviderTest {

    private lateinit var server: MockWebServer
    private lateinit var sut: SearXNGProvider

    @BeforeEach
    fun setUp() {
        server = MockWebServer().apply { start() }
        val baseUrl = server.url("/").toString().trimEnd('/')
        sut = SearXNGProvider(baseUrl = baseUrl, client = OkHttpClient())
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
                .setBody("""{"results":[{"title":"t1","url":"https://x","content":"snip"}]}""")
        )

        val result = sut.search("kotlin coroutines", 5)

        assertTrue(result.isSuccess, "Expected success but got: ${result.exceptionOrNull()?.message}")
        val hits = result.getOrThrow()
        assertEquals(1, hits.size)
        with(hits[0]) {
            assertEquals("t1", title)
            assertEquals("https://x", url)
            assertEquals("snip", snippet)
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
                .setBody("not valid json {{{{")
        )

        val result = sut.search("query", 5)

        assertTrue(result.isFailure)
        assertTrue(
            result.exceptionOrNull()?.message?.contains("PROVIDER_MALFORMED_RESPONSE") == true,
            "Expected PROVIDER_MALFORMED_RESPONSE but got: ${result.exceptionOrNull()?.message}"
        )
    }
}

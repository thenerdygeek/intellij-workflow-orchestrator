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

class CustomHttpProviderTest {

    private lateinit var server: MockWebServer

    @BeforeEach
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun serverBaseUrl(): String = server.url("/").toString().trimEnd('/')

    private fun buildGetProvider(
        path: String = "/search?q={query}",
        resultsPath: String = "$.results",
        titlePath: String = "$.title",
        urlPath: String = "$.url",
        snippetPath: String = "$.snippet",
        headerName: String? = null,
        headerValue: String? = null,
    ): CustomHttpProvider = CustomHttpProvider(
        urlTemplate = serverBaseUrl() + path,
        method = "GET",
        headerName = headerName,
        headerValue = headerValue,
        resultsPath = resultsPath,
        titlePath = titlePath,
        urlPath = urlPath,
        snippetPath = snippetPath,
        client = OkHttpClient(),
    )

    // ── Test cases ─────────────────────────────────────────────────────────────

    @Test
    fun `GET happy path returns hits from top-level results array`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody(
                    """{"results":[{"title":"Corp result","url":"https://corp.example.com","snippet":"A useful snippet"}]}"""
                )
        )

        val result = buildGetProvider().search("kotlin", 5)

        assertTrue(result.isSuccess, "Expected success but got: ${result.exceptionOrNull()?.message}")
        val hits = result.getOrThrow()
        assertEquals(1, hits.size)
        with(hits[0]) {
            assertEquals("Corp result", title)
            assertEquals("https://corp.example.com", url)
            assertEquals("A useful snippet", snippet)
            assertEquals(0, rank)
        }
    }

    @Test
    fun `POST happy path sends empty JSON body and parses results`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody(
                    """{"results":[{"title":"Post result","url":"https://post.example.com","snippet":"Post snippet"}]}"""
                )
        )

        val provider = CustomHttpProvider(
            urlTemplate = serverBaseUrl() + "/api?q={query}",
            method = "POST",
            headerName = null,
            headerValue = null,
            resultsPath = "$.results",
            titlePath = "$.title",
            urlPath = "$.url",
            snippetPath = "$.snippet",
            client = OkHttpClient(),
        )

        val result = provider.search("test query", 5)

        assertTrue(result.isSuccess, "Expected success but got: ${result.exceptionOrNull()?.message}")
        val hits = result.getOrThrow()
        assertEquals(1, hits.size)
        assertEquals("Post result", hits[0].title)

        // Verify the request method was POST
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
    }

    @Test
    fun `missing resultsPath in response returns failure with PROVIDER_MALFORMED_RESPONSE`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("""{"data":{"items":[]}}""") // "results" key is absent
        )

        val result = buildGetProvider(resultsPath = "$.results").search("query", 5)

        assertTrue(result.isFailure)
        assertTrue(
            result.exceptionOrNull()?.message?.contains("PROVIDER_MALFORMED_RESPONSE") == true,
            "Expected PROVIDER_MALFORMED_RESPONSE but got: ${result.exceptionOrNull()?.message}"
        )
    }

    @Test
    fun `401 response returns failure with PROVIDER_AUTH_FAILED`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        val result = buildGetProvider().search("query", 5)

        assertTrue(result.isFailure)
        assertTrue(
            result.exceptionOrNull()?.message?.contains("PROVIDER_AUTH_FAILED") == true,
            "Expected PROVIDER_AUTH_FAILED but got: ${result.exceptionOrNull()?.message}"
        )
    }

    // ── I11: SSRF — {query} in the host segment is rejected at validate() time ─

    @Test
    fun `validate rejects urlTemplate with curly-query in host segment`() = runTest {
        // The B3 SSRF screen runs against the SANDBOX url (with {query} replaced by a
        // placeholder). If {query} is in the host, the placeholder host is screened but
        // the runtime host (which the attacker controls via query) is different. Reject
        // such templates at config validate() time.
        val provider = CustomHttpProvider(
            urlTemplate = "https://{query}.attacker.com/x",
            method = "GET",
            headerName = null,
            headerValue = null,
            resultsPath = "$.results",
            titlePath = "$.title",
            urlPath = "$.url",
            snippetPath = "$.snippet",
            client = OkHttpClient(),
        )
        val result = provider.validate()
        assertTrue(result.isFailure, "Expected validate() to fail when {query} is in host segment")
        assertTrue(
            (result.exceptionOrNull()?.message ?: "").contains("host"),
            "Error must mention 'host'; got: ${result.exceptionOrNull()?.message}"
        )
    }

    @Test
    fun `validate accepts urlTemplate with curly-query only in path or query-string`() = runTest {
        // {query} in the path is fine — the host is fully determined by config.
        val provider = CustomHttpProvider(
            urlTemplate = "https://corp.example.com/api?q={query}",
            method = "GET",
            headerName = null,
            headerValue = null,
            resultsPath = "$.results",
            titlePath = "$.title",
            urlPath = "$.url",
            snippetPath = "$.snippet",
            client = OkHttpClient(),
        )
        assertTrue(provider.validate().isSuccess)
    }
}

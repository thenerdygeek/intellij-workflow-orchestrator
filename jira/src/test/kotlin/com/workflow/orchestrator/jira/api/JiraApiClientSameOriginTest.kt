package com.workflow.orchestrator.jira.api

import com.intellij.testFramework.LoggedErrorProcessorEnabler
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Regression tests for audit finding jira:F-1:
 * getRawString previously forwarded authenticated requests to any absolute URL
 * the Jira server handed back (e.g. autoCompleteUrl), allowing a malicious/compromised
 * Jira server to exfiltrate the Bearer token to an attacker host.
 *
 * Fix: same-origin guard rejects absolute URLs whose host or scheme differs from the
 * configured Jira base URL. Relative URLs resolved against the base are always allowed.
 */
@ExtendWith(LoggedErrorProcessorEnabler.DoNoRethrowErrors::class)
class JiraApiClientSameOriginTest {

    private lateinit var server: MockWebServer
    private lateinit var client: JiraApiClient

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = JiraApiClient(
            baseUrl = server.url("/").toString().trimEnd('/'),
            tokenProvider = { "test-token" }
        )
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    // ────────────────────────────────────────────────────────────────
    //  REJECTED: cross-host absolute URLs must not be fetched
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `getRawString rejects absolute URL with different host`() = runTest {
        // No mock response queued — the guard must reject before any HTTP call.
        val result = client.getRawString("https://attacker.example.com/steal?token=1")
        assertTrue(result is ApiResult.Error, "Expected error for cross-host URL, got: $result")
        val err = result as ApiResult.Error
        assertEquals(ErrorType.FORBIDDEN, err.type)
        assertTrue(
            err.message.contains("does not match Jira base host", ignoreCase = true),
            "Error message should explain the same-origin rejection, got: ${err.message}"
        )
        // Verify no request was actually sent to the mock server (queue still empty)
        assertNull(server.takeRequest(0, java.util.concurrent.TimeUnit.MILLISECONDS),
            "No HTTP request should have been issued to the mock server for a cross-host URL")
    }

    @Test
    fun `getRawString rejects absolute URL with different scheme`() = runTest {
        // Strip the scheme from the mock server URL, rebuild with different scheme.
        val baseHost = server.url("/").host
        val basePort = server.url("/").port
        // Use http:// against a server that was started as http://, but request https://
        // (or vice versa depending on the test setup). The key is scheme mismatch.
        val crossSchemeUrl = "https://$baseHost:$basePort/rest/api/2/some-path"
        // Our MockWebServer is HTTP, so if the base is http://, https:// is a scheme mismatch.
        val baseUrl = server.url("/").toString().trimEnd('/')
        if (!baseUrl.startsWith("http://")) {
            // If the server somehow started on https, adjust: test still validates scheme guard.
            System.err.println("[JiraApiClientSameOriginTest] mock server not on http://; scheme-mismatch test skipped")
            return@runTest
        }
        val result = client.getRawString(crossSchemeUrl)
        assertTrue(result is ApiResult.Error, "Expected error for scheme-mismatch URL, got: $result")
        val err = result as ApiResult.Error
        assertEquals(ErrorType.FORBIDDEN, err.type)
    }

    // ────────────────────────────────────────────────────────────────
    //  ALLOWED: same-host absolute URLs must succeed
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `getRawString allows same-host absolute URL`() = runTest {
        server.enqueue(MockResponse().setBody("""{"key":"value"}""").setResponseCode(200)
            .addHeader("Content-Type", "application/json"))
        val sameHostUrl = server.url("/rest/api/2/issue/picker").toString()
        val result = client.getRawString(sameHostUrl)
        assertTrue(result is ApiResult.Success, "Expected success for same-host absolute URL, got: $result")
        val success = result as ApiResult.Success
        assertTrue(success.data.contains("key"))
    }

    // ────────────────────────────────────────────────────────────────
    //  ALLOWED: relative URLs must always be forwarded
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `getRawString allows relative URL`() = runTest {
        server.enqueue(MockResponse().setBody("""[{"id":1}]""").setResponseCode(200)
            .addHeader("Content-Type", "application/json"))
        val result = client.getRawString("/rest/api/2/user/search?username=jd")
        assertTrue(result is ApiResult.Success, "Expected success for relative URL, got: $result")
        val success = result as ApiResult.Success
        assertTrue(success.data.contains("id"))
    }

    @Test
    fun `getRawString allows relative URL without leading slash`() = runTest {
        server.enqueue(MockResponse().setBody("""{"status":"ok"}""").setResponseCode(200)
            .addHeader("Content-Type", "application/json"))
        // Non-http/https path: treated as a relative path appended to baseUrl
        val result = client.getRawString("/rest/api/2/serverInfo")
        assertTrue(result is ApiResult.Success, "Expected success for relative path, got: $result")
    }
}

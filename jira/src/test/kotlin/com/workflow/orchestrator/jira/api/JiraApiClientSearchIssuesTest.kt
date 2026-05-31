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
 * Tests for [JiraApiClient.searchIssues] validation branches and [escapeJql].
 *
 * JIRA-COV-6: Three defensive branches in searchIssues have no test coverage:
 * (1) text.length > MAX_SEARCH_TEXT_LENGTH → VALIDATION_ERROR, no HTTP call.
 * (2) text.any { it.code < 32 } (control characters) → VALIDATION_ERROR, no HTTP call.
 * (3) looksLikeKey regex switches JQL to key= OR text~ form.
 * The internal escapeJql function is also fully tested here.
 */
@ExtendWith(LoggedErrorProcessorEnabler.DoNoRethrowErrors::class)
class JiraApiClientSearchIssuesTest {

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

    // ── Text length validation ─────────────────────────────────────────────

    @Test
    fun `searchIssues returns VALIDATION_ERROR and makes no HTTP call for text over 500 chars`() = runTest {
        val longText = "A".repeat(501)

        val result = client.searchIssues(longText)

        assertTrue(result.isError, "Text over 500 chars must return an error without any HTTP call")
        val err = result as ApiResult.Error
        assertEquals(ErrorType.VALIDATION_ERROR, err.type,
            "Oversized text must map to VALIDATION_ERROR, got: ${err.type}")
        assertEquals(0, server.requestCount,
            "No HTTP request must be made when input exceeds MAX_SEARCH_TEXT_LENGTH")
    }

    @Test
    fun `searchIssues accepts text of exactly 500 chars and makes an HTTP call`() = runTest {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"maxResults":20,"startAt":0,"total":0,"issues":[]}""")
        )
        val exactText = "A".repeat(500)

        val result = client.searchIssues(exactText)

        assertTrue(result.isSuccess, "Exactly 500 chars must be accepted; got error: $result")
        assertEquals(1, server.requestCount,
            "Exactly one HTTP request must be made for text of exactly MAX_SEARCH_TEXT_LENGTH")
    }

    // ── Control character validation ────────────────────────────────────────

    @Test
    fun `searchIssues returns VALIDATION_ERROR and makes no HTTP call for text with SOH control character`() = runTest {
        // Construct a string containing ASCII code 1 (SOH) which has .code < 32
        val textWithControlChar = "normal" + 1.toChar() + "text"

        val result = client.searchIssues(textWithControlChar)

        assertTrue(result.isError, "Text with SOH control char must return an error without any HTTP call")
        val err = result as ApiResult.Error
        assertEquals(ErrorType.VALIDATION_ERROR, err.type,
            "Control character in text must map to VALIDATION_ERROR, got: ${err.type}")
        assertEquals(0, server.requestCount,
            "No HTTP request must be made when input contains a control character")
    }

    @Test
    fun `searchIssues returns VALIDATION_ERROR for tab character (code 9) in text`() = runTest {
        // Tab is code 9, which is < 32
        val textWithTab = "search\there"

        val result = client.searchIssues(textWithTab)

        assertTrue(result.isError)
        assertEquals(ErrorType.VALIDATION_ERROR, (result as ApiResult.Error).type)
        assertEquals(0, server.requestCount)
    }

    // ── Jira key pattern detection ──────────────────────────────────────────

    @Test
    fun `searchIssues uses key OR text JQL form when text matches Jira key pattern`() = runTest {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"maxResults":20,"startAt":0,"total":0,"issues":[]}""")
        )

        client.searchIssues("PROJ-123")

        val recorded = server.takeRequest()
        val decodedPath = java.net.URLDecoder.decode(recorded.path ?: "", "UTF-8")
        assertTrue(
            decodedPath.contains("key"),
            "Jira key pattern input must switch JQL to include 'key' form; path=$decodedPath"
        )
    }

    @Test
    fun `searchIssues uses plain text JQL form for non-key input`() = runTest {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"maxResults":20,"startAt":0,"total":0,"issues":[]}""")
        )

        client.searchIssues("login bug")

        val recorded = server.takeRequest()
        val decodedPath = java.net.URLDecoder.decode(recorded.path ?: "", "UTF-8")
        assertTrue(
            decodedPath.contains("text ~") || decodedPath.contains("text+~") ||
                decodedPath.contains("text+%7E"),
            "Non-key input must use the text ~ JQL form; path=$decodedPath"
        )
    }
}

/**
 * Pure-Kotlin unit tests for [escapeJql].
 *
 * The function is `internal` (same module) and escapes JQL reserved characters
 * by prepending a backslash. Plain alphanumeric text must pass through unchanged.
 */
class EscapeJqlTest {

    @Test
    fun `escapeJql leaves plain alphanumeric text unchanged`() {
        assertEquals("hello", escapeJql("hello"))
        assertEquals("PROJ123", escapeJql("PROJ123"))
        assertEquals("fix login", escapeJql("fix login"))
    }

    @Test
    fun `escapeJql escapes double quote`() {
        assertEquals("\\\"quoted\\\"", escapeJql("\"quoted\""))
    }

    @Test
    fun `escapeJql escapes plus sign`() {
        assertEquals("a\\+b", escapeJql("a+b"))
    }

    @Test
    fun `escapeJql escapes minus sign`() {
        assertEquals("a\\-b", escapeJql("a-b"))
    }

    @Test
    fun `escapeJql escapes opening parenthesis`() {
        assertEquals("\\(test\\)", escapeJql("(test)"))
    }

    @Test
    fun `escapeJql escapes ampersand`() {
        assertEquals("a\\&b", escapeJql("a&b"))
    }

    @Test
    fun `escapeJql escapes pipe`() {
        assertEquals("a\\|b", escapeJql("a|b"))
    }

    @Test
    fun `escapeJql escapes backslash`() {
        assertEquals("a\\\\b", escapeJql("a\\b"))
    }

    @Test
    fun `escapeJql handles empty string`() {
        assertEquals("", escapeJql(""))
    }

    @Test
    fun `escapeJql escapes multiple reserved chars in one string`() {
        // "fix (login)" → "fix \(login\)"
        assertEquals("fix \\(login\\)", escapeJql("fix (login)"))
    }
}

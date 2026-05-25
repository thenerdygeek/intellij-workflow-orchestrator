package com.workflow.orchestrator.jira.tasks

import com.intellij.testFramework.LoggedErrorProcessorEnabler
import com.workflow.orchestrator.jira.api.JiraApiClient
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Verifies that [JiraTaskFunnel.buildJql] validates the query value before
 * interpolating it into the `key = "..."` JQL branch.
 *
 * Prior to this fix, the key= branch used a non-anchored / narrower regex that
 * could differ from the `validateTicketKeys` path. Using [JiraTaskFunnel.isValidJiraKey]
 * (anchored `^[A-Z][A-Z0-9_]+-\d+$`) in both paths guarantees consistency.
 *
 * Closes audit finding jira:F-7.
 */
@ExtendWith(LoggedErrorProcessorEnabler.DoNoRethrowErrors::class)
class BuildJqlKeyValidationTest {

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

    private fun newFunnel(): JiraTaskFunnel {
        val baseUrl = server.url("/").toString().trimEnd('/')
        val client = JiraApiClient(baseUrl = baseUrl, tokenProvider = { "test-token" })
        return JiraTaskFunnel(
            apiClient = client,
            baseUrlProvider = { baseUrl },
            bridge = { block -> runBlocking { block() } },
        )
    }

    // ── isValidJiraKey helper ────────────────────────────────────────────────────

    @Test
    fun `valid key ABC-1 is accepted`() {
        assertTrue(JiraTaskFunnel.isValidJiraKey("ABC-1"), "ABC-1 must be a valid key")
    }

    @Test
    fun `valid key with underscore PROJECT_NAME-123 is accepted`() {
        assertTrue(JiraTaskFunnel.isValidJiraKey("PROJECT_NAME-123"))
    }

    @Test
    fun `valid key with digits in prefix ABC2-42 is accepted`() {
        assertTrue(JiraTaskFunnel.isValidJiraKey("ABC2-42"))
    }

    @Test
    fun `injection payload is rejected`() {
        // This is the audit-stated injection vector: key contains JQL syntax after the number
        val injection = """ABC-1" OR summary~"x"""
        assertFalse(JiraTaskFunnel.isValidJiraKey(injection), "Injection payload must be rejected")
    }

    @Test
    fun `lowercase prefix is rejected`() {
        assertFalse(JiraTaskFunnel.isValidJiraKey("proj-1"), "Lowercase prefix must be rejected")
    }

    @Test
    fun `blank string is rejected`() {
        assertFalse(JiraTaskFunnel.isValidJiraKey(""), "Blank string must be rejected")
    }

    @Test
    fun `key with trailing whitespace is rejected`() {
        assertFalse(JiraTaskFunnel.isValidJiraKey("ABC-1 "), "Trailing whitespace must be rejected")
    }

    @Test
    fun `key with embedded space is rejected`() {
        assertFalse(JiraTaskFunnel.isValidJiraKey("ABC 1"), "Embedded space must be rejected")
    }

    // ── JQL routing: valid key → key= branch; invalid → summary~ branch ─────────

    @Test
    fun `valid key routes to key= JQL clause and hits search endpoint`() {
        // Enqueue a response; we only care which JQL is sent, not the result
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"maxResults":10,"startAt":0,"total":0,"issues":[]}"""),
        )

        newFunnel().getIssues("PROJ-42", offset = 0, limit = 10, withClosed = false)

        val recorded = server.takeRequest()
        val path = recorded.path ?: ""
        assertTrue(
            path.contains("key+%3D") || path.contains("key+%3d") ||
                path.contains("key%20%3D") || path.contains("key+=") || path.contains("key =") ||
                // URL-decoded form: "key = "
                java.net.URLDecoder.decode(path, "UTF-8").contains("key = \"PROJ-42\""),
            "Valid key must produce key= JQL clause, got path: $path",
        )
    }

    @Test
    fun `text search routes to summary~ JQL clause`() {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"maxResults":10,"startAt":0,"total":0,"issues":[]}"""),
        )

        newFunnel().getIssues("login bug", offset = 0, limit = 10, withClosed = false)

        val recorded = server.takeRequest()
        val decoded = java.net.URLDecoder.decode(recorded.path ?: "", "UTF-8")
        assertTrue(
            decoded.contains("summary ~") || decoded.contains("summary~"),
            "Text search must produce summary~ JQL clause, got: $decoded",
        )
        assertFalse(
            decoded.contains("key =") || decoded.contains("key="),
            "Text search must not produce key= clause, got: $decoded",
        )
    }
}

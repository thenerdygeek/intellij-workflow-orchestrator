package com.workflow.orchestrator.jira.service

import com.intellij.testFramework.LoggedErrorProcessorEnabler
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.jira.api.JiraApiClient
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Tests for [JiraTicketProviderImpl.getTicketContext].
 *
 * Uses [MockWebServer] to serve canned JSON responses and injects the [JiraApiClient]
 * via the [JiraTicketProviderImpl.testClient] seam so IntelliJ services are not required.
 *
 * [LoggedErrorProcessorEnabler.DoNoRethrowErrors] prevents IntelliJ's test framework from
 * converting deliberate log.warn() calls (on HTTP errors) into test failures.
 */
@ExtendWith(LoggedErrorProcessorEnabler.DoNoRethrowErrors::class)
class JiraTicketProviderImplTest {

    private lateinit var server: MockWebServer
    private lateinit var apiClient: JiraApiClient
    private lateinit var provider: JiraTicketProviderImpl

    private fun fixture(name: String): String =
        javaClass.classLoader.getResource("fixtures/$name")!!.readText()

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        apiClient = JiraApiClient(
            baseUrl = server.url("/").toString().trimEnd('/'),
            tokenProvider = { "test-token" }
        )
        provider = JiraTicketProviderImpl().also {
            it.testClient = apiClient
            it.useTestAcceptanceCriteriaFieldId = true  // skip ConnectionSettings lookup
            it.testAcceptanceCriteriaFieldId = null      // no AC field by default
        }
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    // ── Full response ──────────────────────────────────────────────────────────

    @Test
    fun `getTicketContext parses full response with rendered description`() = runTest {
        server.enqueue(
            MockResponse()
                .setBody(fixture("jira-issue-context-full.json"))
                .setHeader("Content-Type", "application/json")
        )

        val context = provider.getTicketContext("PROJ-123")

        assertNotNull(context)
        assertEquals("PROJ-123", context!!.key)
        assertEquals("Fix login page redirect", context.summary)

        // Rendered description should take precedence over raw
        assertEquals("<p>Rendered description from Jira</p>", context.description)

        assertEquals("In Progress", context.status)
        assertEquals("High", context.priority)
        assertEquals("Story", context.issueType)
        assertEquals("John Doe", context.assignee)
        assertEquals("Jane Smith", context.reporter)

        assertEquals(listOf("backend", "auth"), context.labels)
        assertEquals(listOf("Authentication", "API Gateway"), context.components)
        assertEquals(listOf("v2.1.0"), context.fixVersions)

        assertEquals(2, context.comments.size)
        val firstComment = context.comments[0]
        assertEquals("Alice Dev", firstComment.author)
        assertEquals("2026-03-05T09:00:00.000+0000", firstComment.created)
        assertEquals("Investigated root cause — session cookie expiry.", firstComment.body)

        assertNull(context.acceptanceCriteria)

        // Verify HTTP call used the correct endpoint
        val recorded = server.takeRequest()
        assertTrue(recorded.path!!.contains("/rest/api/2/issue/PROJ-123"))
        assertTrue(recorded.path!!.contains("expand=renderedFields"))
        assertEquals("Bearer test-token", recorded.getHeader("Authorization"))
    }

    // ── Missing optional fields ───────────────────────────────────────────────

    @Test
    fun `getTicketContext handles minimal response with no optional fields`() = runTest {
        server.enqueue(
            MockResponse()
                .setBody(fixture("jira-issue-context-minimal.json"))
                .setHeader("Content-Type", "application/json")
        )

        val context = provider.getTicketContext("PROJ-456")

        assertNotNull(context)
        assertEquals("PROJ-456", context!!.key)
        assertEquals("Minimal ticket with no optional fields", context.summary)
        assertEquals("To Do", context.status)

        // Optional fields absent → null / empty collections
        assertNull(context.description)
        assertNull(context.priority)
        assertNull(context.issueType)
        assertNull(context.assignee)
        assertNull(context.reporter)
        assertTrue(context.labels.isEmpty())
        assertTrue(context.components.isEmpty())
        assertTrue(context.fixVersions.isEmpty())
        assertTrue(context.comments.isEmpty())
        assertNull(context.acceptanceCriteria)
    }

    // ── Fallback: raw description when renderedFields absent ──────────────────

    @Test
    fun `getTicketContext falls back to raw description when renderedFields absent`() = runTest {
        server.enqueue(
            MockResponse()
                .setBody(fixture("jira-issue-context-no-rendered.json"))
                .setHeader("Content-Type", "application/json")
        )

        val context = provider.getTicketContext("PROJ-789")

        assertNotNull(context)
        // No renderedFields in response — raw description must be used
        assertEquals("This is the raw wiki-markup description", context!!.description)
        assertEquals("Medium", context.priority)
        assertEquals("Bug", context.issueType)
        assertEquals(listOf("regression"), context.labels)
        assertEquals(listOf("v1.5.0"), context.fixVersions)
        assertTrue(context.comments.isEmpty())
    }

    // ── HTTP error → returns null ─────────────────────────────────────────────

    @Test
    fun `getTicketContext returns null on 404`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))

        val context = provider.getTicketContext("PROJ-NOTFOUND")

        assertNull(context)
    }

    @Test
    fun `getTicketContext returns null on 401`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        val context = provider.getTicketContext("PROJ-UNAUTH")

        assertNull(context)
    }

    // ── Acceptance criteria custom field ─────────────────────────────────────

    @Test
    fun `getTicketContext fetches acceptance criteria when field ID is configured`() = runTest {
        // First request: the main issue context fetch
        server.enqueue(
            MockResponse()
                .setBody(fixture("jira-issue-context-full.json"))
                .setHeader("Content-Type", "application/json")
        )
        // Second request: the custom field fetch
        server.enqueue(
            MockResponse()
                .setBody("""{"id":"10001","key":"PROJ-123","fields":{"customfield_10001":"User can log in with valid credentials."}}""")
                .setHeader("Content-Type", "application/json")
        )

        provider.testAcceptanceCriteriaFieldId = "customfield_10001"

        val context = provider.getTicketContext("PROJ-123")

        assertNotNull(context)
        assertEquals("User can log in with valid credentials.", context!!.acceptanceCriteria)
    }

    @Test
    fun `getTicketContext leaves acceptanceCriteria null when custom field value is absent`() = runTest {
        server.enqueue(
            MockResponse()
                .setBody(fixture("jira-issue-context-full.json"))
                .setHeader("Content-Type", "application/json")
        )
        // Custom field fetch returns null value
        server.enqueue(
            MockResponse()
                .setBody("""{"id":"10001","key":"PROJ-123","fields":{"customfield_10001":null}}""")
                .setHeader("Content-Type", "application/json")
        )

        provider.testAcceptanceCriteriaFieldId = "customfield_10001"

        val context = provider.getTicketContext("PROJ-123")

        assertNotNull(context)
        assertNull(context!!.acceptanceCriteria)
    }

    // ── JiraApiClient.getIssueWithContext HTTP path ───────────────────────────

    @Test
    fun `getIssueWithContext sends correct query parameters`() = runTest {
        server.enqueue(
            MockResponse()
                .setBody(fixture("jira-issue-context-full.json"))
                .setHeader("Content-Type", "application/json")
        )

        val result = apiClient.getIssueWithContext("PROJ-123")

        assertTrue(result is ApiResult.Success)
        val recorded = server.takeRequest()
        val path = recorded.path!!
        assertTrue(path.contains("fields="), "Expected fields param in: $path")
        assertTrue(path.contains("expand=renderedFields"), "Expected expand=renderedFields in: $path")
        assertTrue(path.contains("summary"), "Expected summary in fields: $path")
        assertTrue(path.contains("comment"), "Expected comment in fields: $path")
        assertTrue(path.contains("fixVersions"), "Expected fixVersions in fields: $path")
    }
}

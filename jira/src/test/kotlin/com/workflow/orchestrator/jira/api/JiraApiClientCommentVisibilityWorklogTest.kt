package com.workflow.orchestrator.jira.api

import com.intellij.testFramework.LoggedErrorProcessorEnabler
import com.workflow.orchestrator.core.model.ApiResult
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Pins the wire-format contract for PR 5 of the 2026-05-07 write-ops audit:
 *
 *  1. `addComment` serializes a role-restricted visibility block as
 *     `{"body": "...", "visibility": {"type": "role", "value": "Developers"}}` —
 *     and **omits** the `visibility` key when the caller doesn't pass it
 *     (Jira rejects `"visibility": null`).
 *  2. `postWorklog` forwards the user-picked `started` ISO string into the body
 *     and lifts `adjustEstimate` into a query param when set.
 */
@ExtendWith(LoggedErrorProcessorEnabler.DoNoRethrowErrors::class)
class JiraApiClientCommentVisibilityWorklogTest {

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

    // ── addComment visibility ────────────────────────────────────────────────

    @Test
    fun `addComment with role visibility serializes visibility block`() = runTest {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"100"}"""))

        val result = client.addComment(
            issueKey = "ABC-1",
            body = "ship it",
            visibilityType = "role",
            visibilityValue = "Developers"
        )

        assertTrue(result is ApiResult.Success)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/rest/api/2/issue/ABC-1/comment", recorded.path)
        val body = recorded.body.readUtf8()
        assertTrue(body.contains(""""body":"ship it""""), "Expected body field; got $body")
        assertTrue(
            body.contains(""""visibility":{"type":"role","value":"Developers"}"""),
            "Expected visibility block; got $body"
        )
    }

    @Test
    fun `addComment without visibility omits the visibility field entirely`() = runTest {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"100"}"""))

        val result = client.addComment("ABC-1", body = "hello")

        assertTrue(result is ApiResult.Success)
        val body = server.takeRequest().body.readUtf8()
        // The visibility key must be absent — Jira treats `"visibility": null` as a 400.
        assertFalse(
            body.contains("visibility"),
            "Expected no visibility key when caller passes null; got $body"
        )
    }

    @Test
    fun `addComment with group visibility serializes lowercase type`() = runTest {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"100"}"""))

        client.addComment(
            issueKey = "ABC-1",
            body = "fyi",
            visibilityType = "group",
            visibilityValue = "jira-developers"
        )

        val body = server.takeRequest().body.readUtf8()
        assertTrue(
            body.contains(""""visibility":{"type":"group","value":"jira-developers"}"""),
            "Expected group visibility block; got $body"
        )
    }

    // ── postWorklog: started + adjustEstimate ────────────────────────────────

    @Test
    fun `postWorklog forwards started ISO string in the body`() = runTest {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"77"}"""))

        val started = "2026-05-08T09:00:00.000+0000"
        client.postWorklog(
            issueKey = "ABC-1",
            timeSpent = "1h",
            comment = "lunch",
            started = started
        )

        val recorded = server.takeRequest()
        assertEquals("/rest/api/2/issue/ABC-1/worklog", recorded.path)
        val body = recorded.body.readUtf8()
        assertTrue(body.contains(""""timeSpent":"1h""""), "Expected timeSpent; got $body")
        assertTrue(body.contains(""""comment":"lunch""""), "Expected comment; got $body")
        assertTrue(body.contains(""""started":"$started""""), "Expected started; got $body")
    }

    @Test
    fun `postWorklog without started omits the started field`() = runTest {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"77"}"""))

        client.postWorklog("ABC-1", timeSpent = "30m")

        val body = server.takeRequest().body.readUtf8()
        assertFalse(
            body.contains("started"),
            "Expected no started key when caller omits it; got $body"
        )
    }

    @Test
    fun `postWorklog with adjustEstimate appends query param`() = runTest {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"77"}"""))

        client.postWorklog(
            issueKey = "ABC-1",
            timeSpent = "1h",
            adjustEstimateParam = "new"
        )

        val recorded = server.takeRequest()
        assertEquals(
            "/rest/api/2/issue/ABC-1/worklog?adjustEstimate=new",
            recorded.path,
            "Expected adjustEstimate query param when caller specifies it."
        )
    }

    @Test
    fun `postWorklog with default AUTO adjustEstimate keeps clean URL`() = runTest {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"77"}"""))

        client.postWorklog("ABC-1", timeSpent = "1h", adjustEstimateParam = null)

        val recorded = server.takeRequest()
        assertEquals(
            "/rest/api/2/issue/ABC-1/worklog",
            recorded.path,
            "Expected no adjustEstimate query param when caller passes null."
        )
    }
}

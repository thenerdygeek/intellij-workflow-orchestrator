package com.workflow.orchestrator.jira.tasks

import com.intellij.testFramework.LoggedErrorProcessorEnabler
import com.workflow.orchestrator.jira.api.JiraApiClient
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * R-ARCH-1: confirms [JiraTaskRepository]'s funnel delegate routes through
 * [JiraApiClient] — picking up auth, retry, and the HTML content-type guard — instead
 * of building its own OkHttp client.
 *
 * Tests exercise [JiraTaskFunnel] directly. The thin shell [JiraTaskRepository] only
 * wires `BaseRepositoryImpl` plumbing onto the funnel and uses `runBlockingCancellable`
 * for the bridge; that bridge requires a live IntelliJ `Application`, which would force
 * `BasePlatformTestCase` and conflict with the existing platform fixture in this module
 * (see the indexing-slot quirk called out by `StartWorkDialogActivateOnlyTest`). Splitting
 * the funnel out keeps this test pure JUnit5 + MockWebServer.
 */
@ExtendWith(LoggedErrorProcessorEnabler.DoNoRethrowErrors::class)
class JiraTaskRepositoryFunnelTest {

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
        val client = JiraApiClient(baseUrl = baseUrl, tokenProvider = { "fake-token" })
        return JiraTaskFunnel(
            apiClient = client,
            baseUrlProvider = { baseUrl },
            bridge = { block -> runBlocking { block() } }
        )
    }

    @Test
    fun `findTask hits the funnel with Bearer auth`() {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"id":"1","key":"PROJ-1","fields":{
                       "summary":"hello","status":{"name":"Open","statusCategory":{"key":"new","name":"To Do"}},
                       "issuetype":{"name":"Task"}}}"""
                )
        )

        val task = newFunnel().findTask("PROJ-1")

        assertNotNull(task, "findTask should return a JiraTask on 200")
        assertEquals("PROJ-1", task!!.id)
        assertEquals("hello", task.summary)

        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertTrue(
            recorded.path!!.startsWith("/rest/api/2/issue/PROJ-1"),
            "Expected /rest/api/2/issue/PROJ-1, got ${recorded.path}"
        )
        assertEquals(
            "Bearer fake-token",
            recorded.getHeader("Authorization"),
            "Funnel must inject Bearer auth header"
        )
    }

    @Test
    fun `getIssues hits search endpoint with paging and Bearer auth`() {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"maxResults":10,"startAt":0,"total":1,"issues":[
                       {"id":"1","key":"PROJ-1","fields":{
                         "summary":"first","status":{"name":"Open","statusCategory":{"key":"new","name":"To Do"}},
                         "issuetype":{"name":"Task"}}}
                       ]}"""
                )
        )

        val tasks = newFunnel().getIssues("foo", 0, 10, false)

        assertEquals(1, tasks.size)

        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertTrue(
            recorded.path!!.startsWith("/rest/api/2/search"),
            "Expected /rest/api/2/search, got ${recorded.path}"
        )
        assertTrue(recorded.path!!.contains("startAt=0"), "Expected startAt=0, got ${recorded.path}")
        assertTrue(recorded.path!!.contains("maxResults=10"), "Expected maxResults=10, got ${recorded.path}")
        assertEquals("Bearer fake-token", recorded.getHeader("Authorization"))
    }

    @Test
    fun `findTask returns null on 404 (does not throw)`() {
        server.enqueue(MockResponse().setResponseCode(404))

        val task = newFunnel().findTask("PROJ-9999")

        assertNull(task, "404 must map to null, not an exception")
    }

    @Test
    fun `findTask returns null when funnel HTML guard fires (auth-redirect)`() {
        // The guard maps a 200-with-text/html body (login redirect) to AUTH_FAILED inside the funnel,
        // which the repository surfaces as null. This is the load-bearing assertion that
        // JiraTaskRepository now inherits the funnel's cross-cutting behaviour.
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/html; charset=utf-8")
                .setBody("<html><body>Please log in</body></html>")
        )

        val task = newFunnel().findTask("PROJ-1")

        assertNull(task, "HTML on 200 (auth-redirect) must map to null via funnel guard")
    }

    @Test
    fun `testConnection throws on 401`() {
        server.enqueue(MockResponse().setResponseCode(401))

        assertThrows(Exception::class.java) { newFunnel().testConnection() }
    }

    @Test
    fun `testConnection passes on 200 myself response`() {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"name":"jdoe","displayName":"Jane Doe"}""")
        )

        // Should not throw.
        newFunnel().testConnection()

        val recorded = server.takeRequest()
        assertTrue(
            recorded.path!!.startsWith("/rest/api/2/myself"),
            "Expected /rest/api/2/myself, got ${recorded.path}"
        )
        assertEquals("Bearer fake-token", recorded.getHeader("Authorization"))
    }
}

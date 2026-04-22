package com.workflow.orchestrator.jira.service

import com.intellij.testFramework.LoggedErrorProcessorEnabler
import com.workflow.orchestrator.jira.api.JiraApiClient
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(LoggedErrorProcessorEnabler.DoNoRethrowErrors::class)
class JiraSearchServiceImplTest {

    private lateinit var server: MockWebServer
    private lateinit var apiClient: JiraApiClient
    private lateinit var service: JiraSearchServiceImpl

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        apiClient = JiraApiClient(
            baseUrl = server.url("/").toString().trimEnd('/'),
            tokenProvider = { "test-token" }
        )
        service = JiraSearchServiceImpl().also { it.testClient = apiClient }
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    // ── 1. searchAssignableUsers ───────────────────────────────────────────────

    @Test
    fun `searchAssignableUsers hits correct endpoint and parses users`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """[
                  {"name":"jdoe","displayName":"Jane Doe","emailAddress":"jdoe@example.com",
                   "avatarUrls":{"24x24":"https://example.com/avatar.png"},"active":true},
                  {"name":"jsmith","displayName":"John Smith","emailAddress":"jsmith@example.com",
                   "avatarUrls":{"24x24":"https://example.com/avatar2.png"},"active":false}
                ]"""
            ).setHeader("Content-Type", "application/json")
        )

        val result = service.searchAssignableUsers(ticketKey = "ABC-1", query = "jd", limit = 20)

        assertFalse(result.isError)
        assertEquals(2, result.data.size)
        val first = result.data[0]
        assertEquals("jdoe", first.name)
        assertEquals("Jane Doe", first.displayName)
        assertEquals("jdoe@example.com", first.email)
        assertEquals("https://example.com/avatar.png", first.avatarUrl)
        assertTrue(first.active)
        assertFalse(result.data[1].active)

        val request = server.takeRequest()
        assertTrue(request.path!!.startsWith("/rest/api/2/user/assignable/search"),
            "Expected assignable search path, got: ${request.path}")
        assertTrue(request.path!!.contains("issueKey=ABC-1"),
            "Expected issueKey=ABC-1 in path: ${request.path}")
        assertTrue(request.path!!.contains("query=jd"),
            "Expected query=jd in path: ${request.path}")
    }

    // ── 2. searchUsers ─────────────────────────────────────────────────────────

    @Test
    fun `searchUsers hits correct endpoint and parses users`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """[{"name":"alice","displayName":"Alice","emailAddress":"alice@example.com",
                   "avatarUrls":{"24x24":""},"active":true}]"""
            ).setHeader("Content-Type", "application/json")
        )

        val result = service.searchUsers(query = "alice", limit = 10)

        assertFalse(result.isError)
        assertEquals(1, result.data.size)
        assertEquals("alice", result.data[0].name)
        assertEquals("Alice", result.data[0].displayName)

        val request = server.takeRequest()
        assertTrue(request.path!!.startsWith("/rest/api/2/user/search"),
            "Expected /rest/api/2/user/search, got: ${request.path}")
        assertTrue(request.path!!.contains("query=alice"))
    }

    // ── 3. suggestLabels — happy path ─────────────────────────────────────────

    @Test
    fun `suggestLabels parses suggestions correctly`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"suggestions":[{"label":"backend"},{"label":"bug"},{"label":"backend-api"}]}"""
            ).setHeader("Content-Type", "application/json")
        )

        val result = service.suggestLabels(query = "back", limit = 20)

        assertFalse(result.isError)
        assertEquals(3, result.data.size)
        assertEquals("backend", result.data[0].label)
        assertEquals("bug", result.data[1].label)
        assertEquals("backend-api", result.data[2].label)

        val request = server.takeRequest()
        assertTrue(request.path!!.startsWith("/rest/api/1.0/labels/suggest"),
            "Expected /rest/api/1.0/labels/suggest, got: ${request.path}")
        assertTrue(request.path!!.contains("query=back"))
    }

    // ── 4. suggestLabels — 404 returns empty Success ──────────────────────────

    @Test
    fun `suggestLabels returns empty list as success on 404`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))

        val result = service.suggestLabels(query = "anything", limit = 20)

        assertFalse(result.isError, "404 on label suggest should be ToolResult.isError=false")
        assertTrue(result.data.isEmpty(), "data should be empty list on 404")
    }

    // ── 5. searchGroups ───────────────────────────────────────────────────────

    @Test
    fun `searchGroups hits groups picker and parses response`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"header":"Showing 2 of 2 matching groups","total":2,
                   "groups":[{"name":"jira-software-users"},{"name":"dev-team"}]}"""
            ).setHeader("Content-Type", "application/json")
        )

        val result = service.searchGroups(query = "dev", limit = 20)

        assertFalse(result.isError)
        assertEquals(2, result.data.size)
        assertEquals("jira-software-users", result.data[0].name)
        assertEquals("dev-team", result.data[1].name)

        val request = server.takeRequest()
        assertTrue(request.path!!.startsWith("/rest/api/2/groups/picker"),
            "Expected /rest/api/2/groups/picker, got: ${request.path}")
        assertTrue(request.path!!.contains("query=dev"))
    }

    // ── 6. listVersions — parses correctly ───────────────────────────────────

    @Test
    fun `listVersions hits project versions endpoint and parses`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """[
                  {"id":"10001","name":"1.0.0","released":true,"archived":false},
                  {"id":"10002","name":"1.1.0","released":false,"archived":false}
                ]"""
            ).setHeader("Content-Type", "application/json")
        )

        val result = service.listVersions("MYPROJ")

        assertFalse(result.isError)
        assertEquals(2, result.data.size)
        assertEquals("10001", result.data[0].id)
        assertEquals("1.0.0", result.data[0].name)
        assertTrue(result.data[0].released)
        assertFalse(result.data[0].archived)
        assertEquals("10002", result.data[1].id)
        assertFalse(result.data[1].released)

        val request = server.takeRequest()
        assertTrue(request.path!!.contains("/rest/api/2/project/MYPROJ/versions"),
            "Expected project versions path, got: ${request.path}")
    }

    // ── 7. listVersions — cache within TTL (enqueue once, call twice) ─────────

    @Test
    fun `listVersions caches result within TTL — only one HTTP request for two calls`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """[{"id":"10001","name":"1.0.0","released":true,"archived":false}]"""
            ).setHeader("Content-Type", "application/json")
        )

        val first = service.listVersions("CACHED")
        val second = service.listVersions("CACHED")

        assertFalse(first.isError)
        assertFalse(second.isError)
        assertEquals(1, first.data.size)
        assertEquals(1, second.data.size)
        assertEquals("1.0.0", second.data[0].name)

        // Only one request should have been made
        val request = server.takeRequest()
        assertNotNull(request)
        // The second call should not produce a second request
        assertEquals(0, server.requestCount - 1,
            "Expected exactly 1 HTTP call due to caching, but server received ${server.requestCount}")
    }

    // ── 8. listComponents ─────────────────────────────────────────────────────

    @Test
    fun `listComponents hits project components endpoint and parses`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """[
                  {"id":"20001","name":"Backend","description":"Server-side code"},
                  {"id":"20002","name":"Frontend","description":null}
                ]"""
            ).setHeader("Content-Type", "application/json")
        )

        val result = service.listComponents("MYPROJ")

        assertFalse(result.isError)
        assertEquals(2, result.data.size)
        assertEquals("20001", result.data[0].id)
        assertEquals("Backend", result.data[0].name)
        assertEquals("Server-side code", result.data[0].description)
        assertEquals("20002", result.data[1].id)
        assertNull(result.data[1].description)

        val request = server.takeRequest()
        assertTrue(request.path!!.contains("/rest/api/2/project/MYPROJ/components"),
            "Expected project components path, got: ${request.path}")
    }

    // ── 9a. followAutoCompleteUrl — URL without query param ───────────────────

    @Test
    fun `followAutoCompleteUrl appends query= when URL has no existing params`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """[{"value":"High"},{"value":"Medium"},{"value":"Low"}]"""
            ).setHeader("Content-Type", "application/json")
        )

        // The autocomplete URL already ends with ?query= (from Jira's field metadata);
        // the service should append the actual query value directly.
        val autoCompleteUrl = server.url("/rest/api/2/priority/suggest").toString()
        val result = service.followAutoCompleteUrl(url = autoCompleteUrl, query = "Hi")

        assertFalse(result.isError)
        assertEquals(3, result.data.size)
        assertEquals("High", result.data[0].id)   // id falls back to "value" field
        assertEquals("High", result.data[0].value)

        val request = server.takeRequest()
        assertTrue(request.path!!.contains("query=Hi"),
            "Expected query=Hi in request path, got: ${request.path}")
        // URL had no '?' so separator should be '?'
        assertTrue(request.path!!.contains("?query=Hi"),
            "Expected '?' separator before query, got: ${request.path}")
    }

    // ── 9b. followAutoCompleteUrl — URL already has a query param ─────────────

    @Test
    fun `followAutoCompleteUrl uses ampersand separator when URL already has query params`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """[{"id":"opt1","value":"Option 1"},{"id":"opt2","value":"Option 2"}]"""
            ).setHeader("Content-Type", "application/json")
        )

        val autoCompleteUrl = server.url("/rest/api/2/customfield?fieldId=customfield_10010").toString()
        val result = service.followAutoCompleteUrl(url = autoCompleteUrl, query = "opt")

        assertFalse(result.isError)
        assertEquals(2, result.data.size)
        assertEquals("opt1", result.data[0].id)
        assertEquals("Option 1", result.data[0].value)

        val request = server.takeRequest()
        assertTrue(request.path!!.contains("&query=opt"),
            "Expected '&' separator before query, got: ${request.path}")
    }

    // ── Error propagation ─────────────────────────────────────────────────────

    @Test
    fun `searchUsers propagates server error as ToolResult with isError=true`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))

        val result = service.searchUsers(query = "anyone", limit = 5)

        assertTrue(result.isError, "Expected isError=true for 500 response")
        assertTrue(result.data.isEmpty())
    }
}

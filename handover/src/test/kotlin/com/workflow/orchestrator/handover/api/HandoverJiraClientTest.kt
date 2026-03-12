package com.workflow.orchestrator.handover.api

import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class HandoverJiraClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: HandoverJiraClient

    private fun fixture(name: String): String =
        javaClass.classLoader.getResource("fixtures/$name")!!.readText()

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = HandoverJiraClient(
            baseUrl = server.url("/").toString().trimEnd('/'),
            tokenProvider = { "test-token" }
        )
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `addComment posts wiki markup and returns comment`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("jira-comment-response.json")))

        val result = client.addComment("PROJ-123", "h4. Automation Results\n|| Suite || Status ||")

        assertTrue(result.isSuccess)
        val comment = (result as ApiResult.Success).data
        assertEquals("10042", comment.id)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/rest/api/2/issue/PROJ-123/comment", recorded.path)
        assertEquals("Bearer test-token", recorded.getHeader("Authorization"))
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("Automation Results"))
    }

    @Test
    fun `addComment returns NOT_FOUND on 404`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))

        val result = client.addComment("INVALID-999", "test")

        assertTrue(result.isError)
        assertEquals(ErrorType.NOT_FOUND, (result as ApiResult.Error).type)
    }

    @Test
    fun `logWork sends correct payload`() = runTest {
        server.enqueue(MockResponse().setResponseCode(201).setBody("{}"))

        val result = client.logWork(
            issueKey = "PROJ-123",
            timeSpentSeconds = 14400,
            comment = "Daily development work",
            started = "2026-03-12T09:00:00.000+0000"
        )

        assertTrue(result.isSuccess)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/rest/api/2/issue/PROJ-123/worklog", recorded.path)
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("14400"))
        assertTrue(body.contains("Daily development work"))
        assertTrue(body.contains("2026-03-12"))
    }

    @Test
    fun `logWork without comment omits comment field`() = runTest {
        server.enqueue(MockResponse().setResponseCode(201).setBody("{}"))

        val result = client.logWork(
            issueKey = "PROJ-123",
            timeSpentSeconds = 7200,
            comment = null,
            started = "2026-03-12T09:00:00.000+0000"
        )

        assertTrue(result.isSuccess)
        val body = server.takeRequest().body.readUtf8()
        assertFalse(body.contains("comment"))
    }

    @Test
    fun `logWork returns NOT_FOUND on 404`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))

        val result = client.logWork("INVALID-999", 3600, null, "2026-03-12T09:00:00.000+0000")

        assertTrue(result.isError)
        assertEquals(ErrorType.NOT_FOUND, (result as ApiResult.Error).type)
    }

    @Test
    fun `logWork returns FORBIDDEN on 403`() = runTest {
        server.enqueue(MockResponse().setResponseCode(403))

        val result = client.logWork("PROJ-123", 3600, null, "2026-03-12T09:00:00.000+0000")

        assertTrue(result.isError)
        assertEquals(ErrorType.FORBIDDEN, (result as ApiResult.Error).type)
    }

    @Test
    fun `addComment returns NETWORK_ERROR on IOException`() = runTest {
        server.shutdown()

        val result = client.addComment("PROJ-123", "test")

        assertTrue(result.isError)
        assertEquals(ErrorType.NETWORK_ERROR, (result as ApiResult.Error).type)
    }

    @Test
    fun `getTransitions returns available transitions`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("jira-transitions.json")))

        val result = client.getTransitions("PROJ-123")

        assertTrue(result.isSuccess)
        val transitions = (result as ApiResult.Success).data
        assertEquals(2, transitions.size)
        assertEquals("In Review", transitions[0].name)
        assertEquals("21", transitions[0].id)

        val recorded = server.takeRequest()
        assertEquals("/rest/api/2/issue/PROJ-123/transitions", recorded.path)
    }

    @Test
    fun `transitionIssue sends transition id`() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))

        val result = client.transitionIssue("PROJ-123", "21")

        assertTrue(result.isSuccess)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/rest/api/2/issue/PROJ-123/transitions", recorded.path)
        val body = recorded.body.readUtf8()
        assertTrue(body.contains(""""id":"21""""))
    }

    @Test
    fun `transitionIssue returns AUTH_FAILED on 401`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        val result = client.transitionIssue("PROJ-123", "21")

        assertTrue(result.isError)
        assertEquals(ErrorType.AUTH_FAILED, (result as ApiResult.Error).type)
    }
}

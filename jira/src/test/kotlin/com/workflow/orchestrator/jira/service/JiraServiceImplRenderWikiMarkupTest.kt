package com.workflow.orchestrator.jira.service

import com.intellij.testFramework.LoggedErrorProcessorEnabler
import com.workflow.orchestrator.jira.api.JiraApiClient
import io.mockk.mockk
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
 * Service-layer coverage for Task 3 of the Handover-tab redesign:
 *
 *  - `renderWikiMarkup(text, issueKey)` POSTs to `/rest/api/1.0/render` with the correct JSON
 *    body and returns the raw HTML body on success.
 *  - A 401 response surfaces as an error result rather than a success.
 */
@ExtendWith(LoggedErrorProcessorEnabler.DoNoRethrowErrors::class)
class JiraServiceImplRenderWikiMarkupTest {

    private val project = mockk<com.intellij.openapi.project.Project>(relaxed = true)

    private lateinit var server: MockWebServer
    private lateinit var apiClient: JiraApiClient
    private lateinit var service: JiraServiceImpl

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        apiClient = JiraApiClient(
            baseUrl = server.url("/").toString().trimEnd('/'),
            tokenProvider = { "test-token" }
        )
        service = JiraServiceImpl(project).also { it.testClient = apiClient }
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `renderWikiMarkup posts to rest api 1_0 render with correct body`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("<h2>Hello</h2>"))

        val result = service.renderWikiMarkup("h2. Hello", "AFTER8TE-912")

        assertFalse(result.isError, "expected success, got isError=true: ${result.summary}")
        assertEquals("<h2>Hello</h2>", result.data)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/rest/api/1.0/render", request.path)
        val body = request.body.readUtf8()
        assertTrue(
            body.contains("\"rendererType\":\"atlassian-wiki-renderer\""),
            "body missing rendererType: $body"
        )
        assertTrue(body.contains("\"unrenderedMarkup\""), "body missing unrenderedMarkup: $body")
        assertTrue(body.contains("h2. Hello"), "body missing markup text: $body")
        assertTrue(body.contains("\"issueKey\":\"AFTER8TE-912\""), "body missing issueKey: $body")
    }

    @Test
    fun `renderWikiMarkup returns error on 401`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        val result = service.renderWikiMarkup("h2. Hello", "AFTER8TE-912")

        assertTrue(result.isError, "expected error on 401, got isError=false: ${result.summary}")
    }
}

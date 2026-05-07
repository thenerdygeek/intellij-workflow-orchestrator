package com.workflow.orchestrator.core.services

import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class HttpFormPostTest {

    private lateinit var server: MockWebServer
    private val client = OkHttpClient()

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `postForm sends form-encoded body with the XSRF header`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

        val result = postForm(
            client = client,
            url = server.url("/queue/PLAN-KEY").toString(),
            formFields = mapOf(
                "bamboo.variable.dockerTagsAsJson" to """{"audit-probe":"v0.0.0"}""",
                "executeAllStages" to "true"
            )
        )

        assertTrue(result is ApiResult.Success, "Expected success but got: $result")
        assertEquals("ok", (result as ApiResult.Success).data)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        // Content-Type set by OkHttp's FormBody; charset suffix is normal.
        val contentType = recorded.getHeader("Content-Type") ?: ""
        assertTrue(
            contentType.startsWith("application/x-www-form-urlencoded"),
            "Expected form-encoded Content-Type; got: $contentType"
        )
        assertEquals("no-check", recorded.getHeader("X-Atlassian-Token"))

        val body = recorded.body.readUtf8()
        // FormBody URL-encodes values; verify the field names land and that the
        // body is NOT a JSON object (the bug we're guarding against).
        assertTrue(body.contains("bamboo.variable.dockerTagsAsJson="), "Body missing variable: $body")
        assertTrue(body.contains("executeAllStages=true"), "Body missing executeAllStages: $body")
        assertTrue(!body.startsWith("{"), "Body must not be JSON: $body")
    }

    @Test
    fun `postForm with empty fields still posts and includes the XSRF header`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(""))

        val result = postForm(
            client = client,
            url = server.url("/queue/PLAN").toString(),
            formFields = emptyMap()
        )

        assertTrue(result is ApiResult.Success)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("no-check", recorded.getHeader("X-Atlassian-Token"))
        assertEquals("", recorded.body.readUtf8())
    }

    @Test
    fun `postForm maps 401 to AUTH_FAILED`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        val result = postForm(
            client = client,
            url = server.url("/queue/PLAN").toString(),
            formFields = mapOf("k" to "v")
        )

        assertTrue(result is ApiResult.Error)
        assertEquals(ErrorType.AUTH_FAILED, (result as ApiResult.Error).type)
    }

    @Test
    fun `postForm maps 403 to FORBIDDEN`() = runTest {
        server.enqueue(MockResponse().setResponseCode(403))

        val result = postForm(
            client = client,
            url = server.url("/queue/PLAN").toString(),
            formFields = mapOf("k" to "v")
        )

        assertTrue(result is ApiResult.Error)
        assertEquals(ErrorType.FORBIDDEN, (result as ApiResult.Error).type)
    }

    @Test
    fun `postForm flags HTML response on 200 as AUTH_REDIRECT`() = runTest {
        // Bamboo / Bitbucket auth-expired flow: POST returns 200 OK with text-html login page.
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/html;charset=UTF-8")
                .setBody("<html><body>login required</body></html>")
        )

        val result = postForm(
            client = client,
            url = server.url("/queue/PLAN").toString(),
            formFields = mapOf("k" to "v")
        )

        assertTrue(result is ApiResult.Error, "Expected AUTH_REDIRECT error; got: $result")
        val error = result as ApiResult.Error
        assertEquals(ErrorType.AUTH_REDIRECT, error.type)
        // Actionable message hint
        assertTrue(error.message.contains("Settings", ignoreCase = true) ||
                   error.message.contains("session", ignoreCase = true),
            "Expected actionable message; got: ${error.message}")
    }

    @Test
    fun `postForm propagates extraHeaders without overriding XSRF`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

        postForm(
            client = client,
            url = server.url("/queue/PLAN").toString(),
            formFields = mapOf("k" to "v"),
            extraHeaders = mapOf("X-Custom" to "abc")
        )

        val recorded = server.takeRequest()
        assertEquals("abc", recorded.getHeader("X-Custom"))
        assertEquals("no-check", recorded.getHeader("X-Atlassian-Token"))
        assertNotNull(recorded.getHeader("Accept"))
    }

    @Test
    fun `postForm maps 5xx to SERVER_ERROR`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("internal"))

        val result = postForm(
            client = client,
            url = server.url("/queue/PLAN").toString(),
            formFields = mapOf("k" to "v")
        )

        assertTrue(result is ApiResult.Error)
        assertEquals(ErrorType.SERVER_ERROR, (result as ApiResult.Error).type)
    }
}

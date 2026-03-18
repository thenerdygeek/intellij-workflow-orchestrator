package com.workflow.orchestrator.agent.tools.integration

import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class JiraCommentToolTest {

    private lateinit var server: MockWebServer
    private val project = mockk<Project> { every { basePath } returns "/tmp" }

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun createTool(
        url: String = server.url("/").toString().trimEnd('/'),
        token: String? = "test-token"
    ): JiraCommentTool = JiraCommentTool(
        urlProvider = { url },
        tokenProvider = { token }
    )

    @Test
    fun `execute sends correct POST to comment endpoint`() = runTest {
        val responseBody = """{"id":"12345","body":"Test comment"}"""
        server.enqueue(MockResponse().setBody(responseBody).setResponseCode(201))

        val tool = createTool()
        val params = buildJsonObject {
            put("key", "PROJ-123")
            put("body", "Test comment")
        }
        val result = tool.execute(params, project)

        assertFalse(result.isError)
        assertTrue(result.content.contains("Comment added to PROJ-123"))

        val request = server.takeRequest()
        assertEquals("/rest/api/2/issue/PROJ-123/comment", request.path)
        assertEquals("POST", request.method)
        assertTrue(request.getHeader("Authorization")!!.startsWith("Bearer "))
        assertEquals("application/json", request.getHeader("Accept"))
    }

    @Test
    fun `execute request body contains comment text`() = runTest {
        server.enqueue(MockResponse().setBody("""{"id":"1"}""").setResponseCode(201))

        val tool = createTool()
        val params = buildJsonObject {
            put("key", "PROJ-123")
            put("body", "This is a test comment")
        }
        tool.execute(params, project)

        val request = server.takeRequest()
        val body = request.body.readUtf8()
        assertTrue(body.contains("This is a test comment"))
        assertTrue(body.contains("\"body\""))
    }

    @Test
    fun `execute handles HTTP error response`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))

        val tool = createTool()
        val params = buildJsonObject {
            put("key", "PROJ-999")
            put("body", "comment text")
        }
        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("404"))
        assertTrue(result.content.contains("PROJ-999"))
    }

    @Test
    fun `execute handles HTTP 400 error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(400))

        val tool = createTool()
        val params = buildJsonObject {
            put("key", "PROJ-123")
            put("body", "comment text")
        }
        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("400"))
    }

    @Test
    fun `execute returns error when key parameter is missing`() = runTest {
        val tool = createTool()
        val params = buildJsonObject {
            put("body", "comment text")
        }
        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("'key' parameter required"))
    }

    @Test
    fun `execute returns error when body parameter is missing`() = runTest {
        val tool = createTool()
        val params = buildJsonObject {
            put("key", "PROJ-123")
        }
        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("'body' parameter required"))
    }

    @Test
    fun `execute returns error when URL is not configured`() = runTest {
        val tool = createTool(url = "")
        val params = buildJsonObject {
            put("key", "PROJ-123")
            put("body", "comment text")
        }
        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("Jira URL not configured"))
    }

    @Test
    fun `execute returns error when token is not configured`() = runTest {
        val tool = createTool(token = null)
        val params = buildJsonObject {
            put("key", "PROJ-123")
            put("body", "comment text")
        }
        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("Jira token not configured"))
    }

    @Test
    fun `execute returns error when token is blank`() = runTest {
        val tool = createTool(token = "")
        val params = buildJsonObject {
            put("key", "PROJ-123")
            put("body", "comment text")
        }
        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("Jira token not configured"))
    }

    @Test
    fun `tool metadata is correct`() {
        val tool = createTool()
        assertEquals("jira_comment", tool.name)
        assertTrue(tool.parameters.required.contains("key"))
        assertTrue(tool.parameters.required.contains("body"))
        assertTrue(tool.parameters.properties.containsKey("key"))
        assertTrue(tool.parameters.properties.containsKey("body"))
        assertTrue(tool.allowedWorkers.contains(com.workflow.orchestrator.agent.runtime.WorkerType.TOOLER))
    }

    @Test
    fun `toToolDefinition produces valid schema`() {
        val tool = createTool()
        val def = tool.toToolDefinition()
        assertEquals("function", def.type)
        assertEquals("jira_comment", def.function.name)
        assertTrue(def.function.parameters.properties.containsKey("key"))
        assertTrue(def.function.parameters.properties.containsKey("body"))
        assertTrue(def.function.parameters.required.contains("key"))
        assertTrue(def.function.parameters.required.contains("body"))
    }

    @Test
    fun `execute has positive token estimate for successful response`() = runTest {
        val responseBody = """{"id":"12345","body":"A comment with some content"}"""
        server.enqueue(MockResponse().setBody(responseBody).setResponseCode(201))

        val tool = createTool()
        val params = buildJsonObject {
            put("key", "PROJ-123")
            put("body", "A comment with some content")
        }
        val result = tool.execute(params, project)

        assertFalse(result.isError)
        assertTrue(result.tokenEstimate > 0)
    }
}

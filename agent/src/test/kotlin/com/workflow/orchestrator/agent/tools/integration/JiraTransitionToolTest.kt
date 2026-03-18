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

class JiraTransitionToolTest {

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
    ): JiraTransitionTool = JiraTransitionTool(
        urlProvider = { url },
        tokenProvider = { token }
    )

    @Test
    fun `execute sends correct POST to transitions endpoint`() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))

        val tool = createTool()
        val params = buildJsonObject {
            put("key", "PROJ-123")
            put("transition_id", "31")
        }
        val result = tool.execute(params, project)

        assertFalse(result.isError)
        assertTrue(result.content.contains("Successfully transitioned PROJ-123"))

        val request = server.takeRequest()
        assertEquals("/rest/api/2/issue/PROJ-123/transitions", request.path)
        assertEquals("POST", request.method)
        assertTrue(request.getHeader("Authorization")!!.startsWith("Bearer "))
        assertEquals("application/json", request.getHeader("Accept"))
    }

    @Test
    fun `execute request body contains transition ID`() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))

        val tool = createTool()
        val params = buildJsonObject {
            put("key", "PROJ-123")
            put("transition_id", "41")
        }
        tool.execute(params, project)

        val request = server.takeRequest()
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"transition\""))
        assertTrue(body.contains("\"id\":\"41\""))
    }

    @Test
    fun `execute handles HTTP error response`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))

        val tool = createTool()
        val params = buildJsonObject {
            put("key", "PROJ-999")
            put("transition_id", "31")
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
            put("transition_id", "31")
        }
        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("400"))
    }

    @Test
    fun `execute returns error when key parameter is missing`() = runTest {
        val tool = createTool()
        val params = buildJsonObject {
            put("transition_id", "31")
        }
        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("'key' parameter required"))
    }

    @Test
    fun `execute returns error when transition_id parameter is missing`() = runTest {
        val tool = createTool()
        val params = buildJsonObject {
            put("key", "PROJ-123")
        }
        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("'transition_id' parameter required"))
    }

    @Test
    fun `execute returns error when both parameters are missing`() = runTest {
        val tool = createTool()
        val params = buildJsonObject { }
        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("'key' parameter required"))
    }

    @Test
    fun `execute returns error when URL is not configured`() = runTest {
        val tool = createTool(url = "")
        val params = buildJsonObject {
            put("key", "PROJ-123")
            put("transition_id", "31")
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
            put("transition_id", "31")
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
            put("transition_id", "31")
        }
        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("Jira token not configured"))
    }

    @Test
    fun `tool metadata is correct`() {
        val tool = createTool()
        assertEquals("jira_transition", tool.name)
        assertTrue(tool.parameters.required.contains("key"))
        assertTrue(tool.parameters.required.contains("transition_id"))
        assertTrue(tool.parameters.properties.containsKey("key"))
        assertTrue(tool.parameters.properties.containsKey("transition_id"))
        assertTrue(tool.allowedWorkers.contains(com.workflow.orchestrator.agent.runtime.WorkerType.TOOLER))
    }

    @Test
    fun `toToolDefinition produces valid schema`() {
        val tool = createTool()
        val def = tool.toToolDefinition()
        assertEquals("function", def.type)
        assertEquals("jira_transition", def.function.name)
        assertTrue(def.function.parameters.properties.containsKey("key"))
        assertTrue(def.function.parameters.properties.containsKey("transition_id"))
        assertTrue(def.function.parameters.required.contains("key"))
        assertTrue(def.function.parameters.required.contains("transition_id"))
    }

    @Test
    fun `execute returns successful result with transition details`() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))

        val tool = createTool()
        val params = buildJsonObject {
            put("key", "PROJ-123")
            put("transition_id", "31")
        }
        val result = tool.execute(params, project)

        assertFalse(result.isError)
        assertTrue(result.content.contains("transition ID 31"))
        assertTrue(result.summary.contains("PROJ-123"))
    }
}

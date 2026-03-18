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

class SonarIssuesToolTest {

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
    ): SonarIssuesTool = SonarIssuesTool(
        urlProvider = { url },
        tokenProvider = { token }
    )

    @Test
    fun `execute sends correct GET to issues search endpoint`() = runTest {
        val responseBody = """{"issues":[{"key":"issue-1","severity":"MAJOR"}],"total":1}"""
        server.enqueue(MockResponse().setBody(responseBody).setResponseCode(200))

        val tool = createTool()
        val params = buildJsonObject { put("project_key", "my-project") }
        val result = tool.execute(params, project)

        assertFalse(result.isError)

        val request = server.takeRequest()
        assertTrue(request.path!!.startsWith("/api/issues/search"))
        assertTrue(request.path!!.contains("componentKeys=my-project"))
        assertTrue(request.path!!.contains("resolved=false"))
        assertEquals("GET", request.method)
        assertTrue(request.getHeader("Authorization")!!.startsWith("Bearer "))
        assertEquals("application/json", request.getHeader("Accept"))
    }

    @Test
    fun `execute appends file filter parameter when provided`() = runTest {
        val responseBody = """{"issues":[],"total":0}"""
        server.enqueue(MockResponse().setBody(responseBody).setResponseCode(200))

        val tool = createTool()
        val params = buildJsonObject {
            put("project_key", "my-project")
            put("file", "src/main/java/MyClass.java")
        }
        val result = tool.execute(params, project)

        assertFalse(result.isError)

        val request = server.takeRequest()
        assertTrue(request.path!!.contains("files="))
        assertTrue(result.summary.contains("file:"))
    }

    @Test
    fun `execute returns sanitized response wrapped in external_data tags`() = runTest {
        val responseBody = """{"issues":[{"key":"issue-1"}],"total":1}"""
        server.enqueue(MockResponse().setBody(responseBody).setResponseCode(200))

        val tool = createTool()
        val params = buildJsonObject { put("project_key", "my-project") }
        val result = tool.execute(params, project)

        assertFalse(result.isError)
        assertTrue(result.content.contains("<external_data"))
        assertTrue(result.content.contains("source=\"sonar\""))
        assertTrue(result.content.contains("key=\"my-project\""))
        assertTrue(result.content.contains("warning=\"UNTRUSTED\""))
    }

    @Test
    fun `execute handles HTTP error response`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))

        val tool = createTool()
        val params = buildJsonObject { put("project_key", "invalid-project") }
        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("404"))
        assertTrue(result.content.contains("invalid-project"))
    }

    @Test
    fun `execute handles HTTP 400 error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(400))

        val tool = createTool()
        val params = buildJsonObject { put("project_key", "my-project") }
        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("400"))
    }

    @Test
    fun `execute returns error when project_key parameter is missing`() = runTest {
        val tool = createTool()
        val params = buildJsonObject { }
        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("'project_key' parameter required"))
    }

    @Test
    fun `execute returns error when URL is not configured`() = runTest {
        val tool = createTool(url = "")
        val params = buildJsonObject { put("project_key", "my-project") }
        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("SonarQube URL not configured"))
    }

    @Test
    fun `execute returns error when token is not configured`() = runTest {
        val tool = createTool(token = null)
        val params = buildJsonObject { put("project_key", "my-project") }
        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("SonarQube token not configured"))
    }

    @Test
    fun `execute returns error when token is blank`() = runTest {
        val tool = createTool(token = "")
        val params = buildJsonObject { put("project_key", "my-project") }
        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("SonarQube token not configured"))
    }

    @Test
    fun `tool metadata is correct`() {
        val tool = createTool()
        assertEquals("sonar_issues", tool.name)
        assertTrue(tool.parameters.required.contains("project_key"))
        assertTrue(tool.parameters.properties.containsKey("project_key"))
        assertTrue(tool.parameters.properties.containsKey("file"))
        assertTrue(tool.allowedWorkers.contains(com.workflow.orchestrator.agent.runtime.WorkerType.TOOLER))
    }

    @Test
    fun `toToolDefinition produces valid schema`() {
        val tool = createTool()
        val def = tool.toToolDefinition()
        assertEquals("function", def.type)
        assertEquals("sonar_issues", def.function.name)
        assertTrue(def.function.parameters.properties.containsKey("project_key"))
        assertTrue(def.function.parameters.properties.containsKey("file"))
        assertTrue(def.function.parameters.required.contains("project_key"))
    }

    @Test
    fun `execute has positive token estimate for successful response`() = runTest {
        val responseBody = """{"issues":[{"key":"issue-1","severity":"MAJOR","message":"Bug found"}],"total":1}"""
        server.enqueue(MockResponse().setBody(responseBody).setResponseCode(200))

        val tool = createTool()
        val params = buildJsonObject { put("project_key", "my-project") }
        val result = tool.execute(params, project)

        assertFalse(result.isError)
        assertTrue(result.tokenEstimate > 0)
    }
}

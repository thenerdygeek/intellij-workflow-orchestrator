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

class BambooBuildToolTest {

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
    ): BambooBuildTool = BambooBuildTool(
        urlProvider = { url },
        tokenProvider = { token }
    )

    @Test
    fun `execute sends correct request path and headers`() = runTest {
        val responseBody = """{"buildState":"Successful","buildNumber":42,"planName":"My Plan"}"""
        server.enqueue(MockResponse().setBody(responseBody).setResponseCode(200))

        val tool = createTool()
        val params = buildJsonObject { put("plan_key", "PROJ-PLAN") }
        val result = tool.execute(params, project)

        assertFalse(result.isError)
        assertTrue(result.content.contains("Successful"))

        val request = server.takeRequest()
        assertEquals("/rest/api/latest/result/PROJ-PLAN/latest", request.path)
        assertEquals("GET", request.method)
        assertTrue(request.getHeader("Authorization")!!.startsWith("Bearer "))
        assertEquals("application/json", request.getHeader("Accept"))
    }

    @Test
    fun `execute returns sanitized response wrapped in external_data tags`() = runTest {
        val responseBody = """{"buildState":"Failed","buildNumber":43}"""
        server.enqueue(MockResponse().setBody(responseBody).setResponseCode(200))

        val tool = createTool()
        val params = buildJsonObject { put("plan_key", "PROJ-PLAN") }
        val result = tool.execute(params, project)

        assertFalse(result.isError)
        assertTrue(result.content.contains("<external_data"))
        assertTrue(result.content.contains("source=\"bamboo\""))
        assertTrue(result.content.contains("key=\"PROJ-PLAN\""))
        assertTrue(result.content.contains("warning=\"UNTRUSTED\""))
    }

    @Test
    fun `execute handles HTTP error response`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))

        val tool = createTool()
        val params = buildJsonObject { put("plan_key", "PROJ-INVALID") }
        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("404"))
        assertTrue(result.content.contains("PROJ-INVALID"))
    }

    @Test
    fun `execute handles HTTP 400 error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(400))

        val tool = createTool()
        val params = buildJsonObject { put("plan_key", "PROJ-PLAN") }
        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("400"))
    }

    @Test
    fun `execute returns error when plan_key parameter is missing`() = runTest {
        val tool = createTool()
        val params = buildJsonObject { }
        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("'plan_key' parameter required"))
    }

    @Test
    fun `execute returns error when URL is not configured`() = runTest {
        val tool = createTool(url = "")
        val params = buildJsonObject { put("plan_key", "PROJ-PLAN") }
        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("Bamboo URL not configured"))
    }

    @Test
    fun `execute returns error when token is not configured`() = runTest {
        val tool = createTool(token = null)
        val params = buildJsonObject { put("plan_key", "PROJ-PLAN") }
        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("Bamboo token not configured"))
    }

    @Test
    fun `execute returns error when token is blank`() = runTest {
        val tool = createTool(token = "")
        val params = buildJsonObject { put("plan_key", "PROJ-PLAN") }
        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("Bamboo token not configured"))
    }

    @Test
    fun `tool metadata is correct`() {
        val tool = createTool()
        assertEquals("bamboo_build_status", tool.name)
        assertTrue(tool.parameters.required.contains("plan_key"))
        assertTrue(tool.parameters.properties.containsKey("plan_key"))
        assertTrue(tool.allowedWorkers.contains(com.workflow.orchestrator.agent.runtime.WorkerType.TOOLER))
        assertTrue(tool.allowedWorkers.contains(com.workflow.orchestrator.agent.runtime.WorkerType.ORCHESTRATOR))
    }

    @Test
    fun `toToolDefinition produces valid schema`() {
        val tool = createTool()
        val def = tool.toToolDefinition()
        assertEquals("function", def.type)
        assertEquals("bamboo_build_status", def.function.name)
        assertTrue(def.function.parameters.properties.containsKey("plan_key"))
        assertTrue(def.function.parameters.required.contains("plan_key"))
    }

    @Test
    fun `execute has positive token estimate for successful response`() = runTest {
        val responseBody = """{"buildState":"Successful","buildNumber":42,"planName":"My Plan","stages":[]}"""
        server.enqueue(MockResponse().setBody(responseBody).setResponseCode(200))

        val tool = createTool()
        val params = buildJsonObject { put("plan_key", "PROJ-PLAN") }
        val result = tool.execute(params, project)

        assertFalse(result.isError)
        assertTrue(result.tokenEstimate > 0)
    }

    @Test
    fun `execute returns successful result with build data`() = runTest {
        val responseBody = """{"buildState":"Successful","buildNumber":100,"duration":12345}"""
        server.enqueue(MockResponse().setBody(responseBody).setResponseCode(200))

        val tool = createTool()
        val params = buildJsonObject { put("plan_key", "PROJ-PLAN") }
        val result = tool.execute(params, project)

        assertFalse(result.isError)
        assertTrue(result.content.contains("Successful"))
        assertTrue(result.content.contains("12345"))
        assertTrue(result.summary.contains("PROJ-PLAN"))
    }
}

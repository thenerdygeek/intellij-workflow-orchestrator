package com.workflow.orchestrator.sonar.api

import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SonarApiClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: SonarApiClient

    private fun fixture(name: String): String =
        javaClass.classLoader.getResource("fixtures/$name")!!.readText()

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = SonarApiClient(
            baseUrl = server.url("/").toString().trimEnd('/'),
            tokenProvider = { "test-token" }
        )
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `validateConnection returns true for valid token`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("auth-validate.json")))

        val result = client.validateConnection()

        assertTrue(result.isSuccess)
        assertTrue((result as ApiResult.Success).data)
        val req = server.takeRequest()
        assertEquals("/api/authentication/validate", req.path)
        assertEquals("Bearer test-token", req.getHeader("Authorization"))
    }

    @Test
    fun `searchProjects returns matching projects`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("projects-search.json")))

        val result = client.searchProjects("my-app")

        assertTrue(result.isSuccess)
        val projects = (result as ApiResult.Success).data
        assertEquals(2, projects.size)
        assertEquals("com.myapp:my-app", projects[0].key)
    }

    @Test
    fun `getQualityGateStatus returns gate with conditions`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("qualitygate-status-passed.json")))

        val result = client.getQualityGateStatus("com.myapp:my-app")

        assertTrue(result.isSuccess)
        val gate = (result as ApiResult.Success).data
        assertEquals("OK", gate.status)
        assertEquals(3, gate.conditions.size)
    }

    @Test
    fun `getQualityGateStatus includes branch parameter`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("qualitygate-status-passed.json")))

        client.getQualityGateStatus("com.myapp:my-app", branch = "feature/test")

        val req = server.takeRequest()
        assertTrue(req.path!!.contains("branch=feature"))
    }

    @Test
    fun `getIssues returns issues with text ranges`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("issues-search.json")))

        val result = client.getIssues("com.myapp:my-app")

        assertTrue(result.isSuccess)
        val issues = (result as ApiResult.Success).data
        assertEquals(3, issues.size)
        assertEquals("BUG", issues[0].type)
        assertEquals(42, issues[0].textRange?.startLine)
        assertNull(issues[2].textRange)
    }

    @Test
    fun `getMeasures returns per-file coverage`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("measures-component-tree.json")))

        val result = client.getMeasures("com.myapp:my-app")

        assertTrue(result.isSuccess)
        val components = (result as ApiResult.Success).data
        assertEquals(2, components.size)
        val coverage = components[0].measures.first { it.metric == "coverage" }
        assertEquals("72.1", coverage.value)
    }

    @Test
    fun `getSourceLines returns per-line coverage data`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("source-lines.json")))

        val result = client.getSourceLines("com.myapp:my-app:src/main/kotlin/com/myapp/service/UserService.kt")

        assertTrue(result.isSuccess)
        val lines = (result as ApiResult.Success).data
        assertEquals(8, lines.size)
        assertEquals(5, lines[0].lineHits)
        assertEquals(0, lines[6].lineHits)
    }

    @Test
    fun `returns AUTH_FAILED on 401`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        val result = client.validateConnection()

        assertTrue(result.isError)
        assertEquals(ErrorType.AUTH_FAILED, (result as ApiResult.Error).type)
    }

    @Test
    fun `returns NOT_FOUND on 404`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))

        val result = client.getQualityGateStatus("nonexistent")

        assertTrue(result.isError)
        assertEquals(ErrorType.NOT_FOUND, (result as ApiResult.Error).type)
    }
}

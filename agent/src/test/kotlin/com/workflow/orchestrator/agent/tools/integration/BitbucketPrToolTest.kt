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

class BitbucketPrToolTest {

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
        token: String? = "test-token",
        projectKey: String = "PROJ",
        repoSlug: String = "my-repo"
    ): BitbucketPrTool = BitbucketPrTool(
        urlProvider = { url },
        tokenProvider = { token },
        projectKeyProvider = { projectKey },
        repoSlugProvider = { repoSlug }
    )

    @Test
    fun `execute sends correct POST to pull-requests endpoint`() = runTest {
        val responseBody = """{"id":1,"title":"My PR","state":"OPEN"}"""
        server.enqueue(MockResponse().setBody(responseBody).setResponseCode(201))

        val tool = createTool()
        val params = buildJsonObject {
            put("title", "My PR")
            put("description", "PR description")
            put("from_branch", "feature/test")
        }
        val result = tool.execute(params, project)

        assertFalse(result.isError)
        assertTrue(result.content.contains("Pull request created"))

        val request = server.takeRequest()
        assertEquals("/rest/api/1.0/projects/PROJ/repos/my-repo/pull-requests", request.path)
        assertEquals("POST", request.method)
        assertTrue(request.getHeader("Authorization")!!.startsWith("Bearer "))
        assertEquals("application/json", request.getHeader("Accept"))
    }

    @Test
    fun `execute request body contains title description and branches`() = runTest {
        val responseBody = """{"id":1,"title":"My PR"}"""
        server.enqueue(MockResponse().setBody(responseBody).setResponseCode(201))

        val tool = createTool()
        val params = buildJsonObject {
            put("title", "My PR")
            put("description", "PR description")
            put("from_branch", "feature/test")
            put("to_branch", "develop")
        }
        tool.execute(params, project)

        val request = server.takeRequest()
        val body = request.body.readUtf8()
        assertTrue(body.contains("My PR"))
        assertTrue(body.contains("PR description"))
        assertTrue(body.contains("refs/heads/feature/test"))
        assertTrue(body.contains("refs/heads/develop"))
    }

    @Test
    fun `execute defaults to_branch to master when not specified`() = runTest {
        val responseBody = """{"id":1,"title":"My PR"}"""
        server.enqueue(MockResponse().setBody(responseBody).setResponseCode(201))

        val tool = createTool()
        val params = buildJsonObject {
            put("title", "My PR")
            put("description", "desc")
            put("from_branch", "feature/test")
        }
        tool.execute(params, project)

        val request = server.takeRequest()
        val body = request.body.readUtf8()
        assertTrue(body.contains("refs/heads/master"))
    }

    @Test
    fun `execute properly escapes branch names with special characters`() = runTest {
        val responseBody = """{"id":1,"title":"PR"}"""
        server.enqueue(MockResponse().setBody(responseBody).setResponseCode(201))

        val tool = createTool()
        val params = buildJsonObject {
            put("title", "Title with \"quotes\"")
            put("description", "Desc with\nnewline")
            put("from_branch", "feature/PROJ-123")
        }
        tool.execute(params, project)

        val request = server.takeRequest()
        val body = request.body.readUtf8()
        assertTrue(body.contains("feature/PROJ-123"))
        // Quotes should be escaped in JSON
        assertTrue(body.contains("\\\"quotes\\\""))
    }

    @Test
    fun `execute handles HTTP error response`() = runTest {
        server.enqueue(MockResponse().setResponseCode(409))

        val tool = createTool()
        val params = buildJsonObject {
            put("title", "My PR")
            put("description", "desc")
            put("from_branch", "feature/test")
        }
        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("409"))
    }

    @Test
    fun `execute handles HTTP 400 error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(400))

        val tool = createTool()
        val params = buildJsonObject {
            put("title", "My PR")
            put("description", "desc")
            put("from_branch", "feature/test")
        }
        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("400"))
    }

    @Test
    fun `execute returns error when title parameter is missing`() = runTest {
        val tool = createTool()
        val params = buildJsonObject {
            put("description", "desc")
            put("from_branch", "feature/test")
        }
        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("'title' parameter required"))
    }

    @Test
    fun `execute returns error when description parameter is missing`() = runTest {
        val tool = createTool()
        val params = buildJsonObject {
            put("title", "My PR")
            put("from_branch", "feature/test")
        }
        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("'description' parameter required"))
    }

    @Test
    fun `execute returns error when from_branch parameter is missing`() = runTest {
        val tool = createTool()
        val params = buildJsonObject {
            put("title", "My PR")
            put("description", "desc")
        }
        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("'from_branch' parameter required"))
    }

    @Test
    fun `execute returns error when URL is not configured`() = runTest {
        val tool = createTool(url = "")
        val params = buildJsonObject {
            put("title", "My PR")
            put("description", "desc")
            put("from_branch", "feature/test")
        }
        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("Bitbucket URL not configured"))
    }

    @Test
    fun `execute returns error when token is not configured`() = runTest {
        val tool = createTool(token = null)
        val params = buildJsonObject {
            put("title", "My PR")
            put("description", "desc")
            put("from_branch", "feature/test")
        }
        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("Bitbucket token not configured"))
    }

    @Test
    fun `execute returns error when token is blank`() = runTest {
        val tool = createTool(token = "")
        val params = buildJsonObject {
            put("title", "My PR")
            put("description", "desc")
            put("from_branch", "feature/test")
        }
        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("Bitbucket token not configured"))
    }

    @Test
    fun `execute returns error when project key is blank`() = runTest {
        val tool = createTool(projectKey = "", repoSlug = "")
        val params = buildJsonObject {
            put("title", "My PR")
            put("description", "desc")
            put("from_branch", "feature/test")
        }
        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("project key or repo slug not configured"))
    }

    @Test
    fun `tool metadata is correct`() {
        val tool = createTool()
        assertEquals("bitbucket_create_pr", tool.name)
        assertTrue(tool.parameters.required.contains("title"))
        assertTrue(tool.parameters.required.contains("description"))
        assertTrue(tool.parameters.required.contains("from_branch"))
        assertTrue(tool.parameters.properties.containsKey("title"))
        assertTrue(tool.parameters.properties.containsKey("description"))
        assertTrue(tool.parameters.properties.containsKey("from_branch"))
        assertTrue(tool.parameters.properties.containsKey("to_branch"))
        assertTrue(tool.allowedWorkers.contains(com.workflow.orchestrator.agent.runtime.WorkerType.TOOLER))
    }

    @Test
    fun `toToolDefinition produces valid schema`() {
        val tool = createTool()
        val def = tool.toToolDefinition()
        assertEquals("function", def.type)
        assertEquals("bitbucket_create_pr", def.function.name)
        assertTrue(def.function.parameters.properties.containsKey("title"))
        assertTrue(def.function.parameters.required.contains("title"))
    }

    @Test
    fun `execute has positive token estimate for successful response`() = runTest {
        val responseBody = """{"id":1,"title":"My PR","state":"OPEN","links":{"self":[]}}"""
        server.enqueue(MockResponse().setBody(responseBody).setResponseCode(201))

        val tool = createTool()
        val params = buildJsonObject {
            put("title", "My PR")
            put("description", "A pull request with some content")
            put("from_branch", "feature/test")
        }
        val result = tool.execute(params, project)

        assertFalse(result.isError)
        assertTrue(result.tokenEstimate > 0)
    }
}

package com.workflow.orchestrator.core.bitbucket

import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class BitbucketBranchClientGetRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var client: BitbucketBranchClient

    @BeforeEach
    fun setup() {
        server = MockWebServer()
        server.start()
        client = BitbucketBranchClient(
            baseUrl = server.url("/").toString().trimEnd('/'),
            tokenProvider = { "test-token" }
        )
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `200 returns Success with parsed BitbucketRepoDetail`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "slug": "my-repo",
                  "name": "My Repo",
                  "project": { "key": "PROJ", "name": "Project" },
                  "links": {
                    "clone": [
                      { "name": "http", "href": "https://bitbucket.example.com/scm/PROJ/my-repo.git" },
                      { "name": "ssh",  "href": "ssh://git@bitbucket.example.com:7999/PROJ/my-repo.git" }
                    ],
                    "self": [
                      { "name": "self", "href": "https://bitbucket.example.com/projects/PROJ/repos/my-repo" }
                    ]
                  }
                }
                """.trimIndent()
            )
        )

        val result = client.getRepository("PROJ", "my-repo")

        assertTrue(result is ApiResult.Success, "Expected Success, got $result")
        val detail = (result as ApiResult.Success).data
        assertEquals("my-repo", detail.slug)
        assertEquals("My Repo", detail.name)
        assertEquals("PROJ", detail.project.key)
        assertEquals(2, detail.links.clone.size)
        val httpClone = detail.links.clone.firstOrNull { it.name == "http" }
        assertEquals("https://bitbucket.example.com/scm/PROJ/my-repo.git", httpClone?.href)

        val req = server.takeRequest()
        assertTrue(req.path?.contains("/rest/api/1.0/projects/PROJ/repos/my-repo") == true)
    }

    @Test
    fun `404 returns Error with NOT_FOUND type`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(404).setBody(
                """{"errors":[{"message":"Repository PROJ/unknown does not exist."}]}"""
            )
        )

        val result = client.getRepository("PROJ", "unknown")

        assertTrue(result is ApiResult.Error, "Expected Error, got $result")
        assertEquals(ErrorType.NOT_FOUND, (result as ApiResult.Error).type)
    }

    @Test
    fun `401 returns Error with AUTH_FAILED type`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(401).setBody(
                """{"errors":[{"message":"You are not authenticated."}]}"""
            )
        )

        val result = client.getRepository("PROJ", "my-repo")

        assertTrue(result is ApiResult.Error, "Expected Error, got $result")
        assertEquals(ErrorType.AUTH_FAILED, (result as ApiResult.Error).type)
    }

    @Test
    fun `403 returns Error with AUTH_FAILED type`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(403).setBody(
                """{"errors":[{"message":"You do not have permission to access this resource."}]}"""
            )
        )

        val result = client.getRepository("PROJ", "my-repo")

        assertTrue(result is ApiResult.Error, "Expected Error, got $result")
        assertEquals(ErrorType.AUTH_FAILED, (result as ApiResult.Error).type)
    }

    @Test
    fun `500 returns Error with NETWORK_ERROR type`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("Internal Server Error"))

        val result = client.getRepository("PROJ", "my-repo")

        assertTrue(result is ApiResult.Error, "Expected Error, got $result")
        assertEquals(ErrorType.NETWORK_ERROR, (result as ApiResult.Error).type)
    }

    @Test
    fun `request targets correct REST path`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"slug":"r","project":{"key":"P","name":"P"},"links":{"clone":[],"self":[]}}"""
            )
        )

        client.getRepository("MYPROJECT", "my-service")

        val req = server.takeRequest()
        assertEquals("/rest/api/1.0/projects/MYPROJECT/repos/my-service", req.path)
    }
}

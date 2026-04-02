package com.workflow.orchestrator.core.bitbucket

import com.workflow.orchestrator.core.bitbucket.BitbucketBranchClient
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import com.intellij.testFramework.LoggedErrorProcessorEnabler
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(LoggedErrorProcessorEnabler.DoNoRethrowErrors::class)
class BitbucketApiClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: BitbucketBranchClient

    private fun fixture(name: String): String =
        javaClass.classLoader.getResource("fixtures/$name")!!.readText()

    @BeforeEach
    fun setUp() {
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
    fun `createPullRequest sends correct payload and returns PR`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("bitbucket-pr-created.json")))

        val result = client.createPullRequest(
            projectKey = "PROJ",
            repoSlug = "my-service",
            title = "PROJ-123: Add login feature",
            description = "Cody-generated description",
            fromBranch = "feature/PROJ-123-add-login",
            toBranch = "develop"
        )

        assertTrue(result.isSuccess)
        val pr = (result as ApiResult.Success).data
        assertEquals(42, pr.id)
        assertEquals("OPEN", pr.state)
        assertTrue(pr.links.self[0].href.contains("pull-requests/42"))

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals(
            "/rest/api/1.0/projects/PROJ/repos/my-service/pull-requests",
            recorded.path
        )
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("refs/heads/feature/PROJ-123-add-login"))
        assertTrue(body.contains("refs/heads/develop"))
    }

    @Test
    fun `createPullRequest returns error on 409 conflict`() = runTest {
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"errors":[{"message":"Already exists"}]}"""))

        val result = client.createPullRequest("PROJ", "my-service", "title", "desc", "branch", "develop")

        assertTrue(result.isError)
        assertEquals(ErrorType.VALIDATION_ERROR, (result as ApiResult.Error).type)
    }

    @Test
    fun `createPullRequest returns FORBIDDEN on 403`() = runTest {
        server.enqueue(MockResponse().setResponseCode(403))

        val result = client.createPullRequest("PROJ", "repo", "title", "desc", "branch", "develop")

        assertTrue(result.isError)
        assertEquals(ErrorType.FORBIDDEN, (result as ApiResult.Error).type)
    }

    @Test
    fun `getPullRequestsForBranch returns matching PRs`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("bitbucket-pr-list.json")))

        val result = client.getPullRequestsForBranch("PROJ", "my-service", "feature/PROJ-123")

        assertTrue(result.isSuccess)
        val prs = (result as ApiResult.Success).data
        assertEquals(1, prs.size)
        assertEquals(42, prs[0].id)

        val recorded = server.takeRequest()
        assertTrue(recorded.path!!.contains("state=OPEN"))
        assertTrue(recorded.path!!.contains("at=refs/heads/feature/PROJ-123"))
    }
}

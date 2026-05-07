package com.workflow.orchestrator.core.bitbucket

import com.workflow.orchestrator.core.model.ApiResult
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Tests for [BitbucketBranchClient.getUsers] — pinning the
 * `(filter, projectKey, repoSlug)` triple's URL shape. The repo-aware
 * variant is what the audit's P1 bonus fix wired into
 * `PrDetailPanel.showAddReviewerPopup` (PR 3 of the 2026-05-07 write-ops
 * fix plan): without it, the reviewer dropdown shows users who can't read
 * the target repo.
 */
class BitbucketBranchClientGetUsersTest {

    private lateinit var server: MockWebServer
    private lateinit var client: BitbucketBranchClient

    @BeforeEach
    fun setup() {
        server = MockWebServer()
        server.start()
        client = BitbucketBranchClient(
            baseUrl = server.url("/").toString().trimEnd('/'),
            tokenProvider = { "test-token" },
        )
    }

    @AfterEach
    fun tearDown() { server.shutdown() }

    private val twoUserResponse = """{
        "values": [
            {"name":"alice", "displayName": "Alice", "emailAddress": "alice@x.com"},
            {"name":"bob",   "displayName": "Bob",   "emailAddress": "bob@x.com"}
        ],
        "size": 2,
        "isLastPage": true
    }""".trimIndent()

    @Test
    fun `getUsers without project and repo issues a global filter-only query`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(twoUserResponse))

        val result = client.getUsers("ali")

        assertTrue(result is ApiResult.Success)
        assertEquals(2, (result as ApiResult.Success).data.size)

        val req = server.takeRequest()
        val path = req.path!!
        assertTrue(path.startsWith("/rest/api/1.0/users?filter=ali"), "Expected user-search path; got $path")
        assertFalse(path.contains("permission.1"),
            "Without projectKey/repoSlug the request must NOT carry permission filters; got $path")
    }

    @Test
    fun `getUsers with project and repo forwards REPO_READ permission filter`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(twoUserResponse))

        val result = client.getUsers("ali", projectKey = "PROJ", repoSlug = "my-repo")

        assertTrue(result is ApiResult.Success)
        val req = server.takeRequest()
        val path = req.path!!
        assertTrue(path.contains("permission.1=REPO_READ"),
            "Repo-scoped query must request REPO_READ; got $path")
        assertTrue(path.contains("permission.1.projectKey=PROJ"),
            "Must forward projectKey; got $path")
        assertTrue(path.contains("permission.1.repositorySlug=my-repo"),
            "Must forward repoSlug; got $path")
    }

    @Test
    fun `getUsers URL-encodes project key and repo slug with special characters`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(twoUserResponse))

        client.getUsers("ali", projectKey = "P&P", repoSlug = "repo with space")

        val req = server.takeRequest()
        val path = req.path!!
        assertTrue(
            path.contains("permission.1.projectKey=P%26P"),
            "projectKey must be URL-encoded; got $path"
        )
        assertTrue(
            path.contains("permission.1.repositorySlug=repo+with+space") ||
                path.contains("permission.1.repositorySlug=repo%20with%20space"),
            "repoSlug must be URL-encoded; got $path"
        )
    }

    @Test
    fun `getUsers treats blank project or repo as global query`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(twoUserResponse))

        client.getUsers("ali", projectKey = "", repoSlug = "")

        val req = server.takeRequest()
        assertFalse(req.path!!.contains("permission.1"),
            "Blank projectKey/repoSlug must skip the permission filter; got ${req.path}")
    }
}

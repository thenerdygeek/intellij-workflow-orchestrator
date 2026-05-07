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

/**
 * Tests for [BitbucketBranchClient.mergePullRequestWithRetry] — the merge-side
 * counterpart to [BitbucketBranchClient.modifyPullRequest], introduced by PR 3
 * of the 2026-05-07 write-ops fix plan.
 *
 * The helper must:
 *  - GET the PR for fresh `version`, POST `/merge?version=<fresh>`.
 *  - On 409, refetch and retry once.
 *  - On second 409, return [ErrorType.STALE_VERSION].
 */
class BitbucketBranchClientMergeWithRetryTest {

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

    private val basePrV3 = """{
        "id": 42,
        "title": "Ready to merge",
        "description": "",
        "state": "OPEN",
        "version": 3,
        "links": {"self": [{"href": "https://example/pr/42"}]}
    }""".trimIndent()

    private val mergedPr = """{
        "id": 42,
        "title": "Ready to merge",
        "description": "",
        "state": "MERGED",
        "version": 4,
        "links": {"self": [{"href": "https://example/pr/42"}]}
    }""".trimIndent()

    @Test
    fun `mergePullRequestWithRetry succeeds on first attempt`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(basePrV3))   // GET
        server.enqueue(MockResponse().setResponseCode(200).setBody(mergedPr))   // POST /merge

        val result = client.mergePullRequestWithRetry(
            repo = RepoCoords("PROJ", "repo"),
            prId = 42,
            strategyId = "no-ff",
            deleteSourceBranch = true,
            commitMessage = "Merge PR #42",
        )

        assertTrue(result is ApiResult.Success, "Expected success; got: $result")
        assertEquals("MERGED", (result as ApiResult.Success).data.state)

        val getReq = server.takeRequest()
        assertEquals("GET", getReq.method)
        val mergeReq = server.takeRequest()
        assertEquals("POST", mergeReq.method)
        assertTrue(
            mergeReq.path!!.contains("/merge?version=3"),
            "Expected merge POST URL to carry fetched version 3, got ${mergeReq.path}"
        )
        // Body carries strategy + delete-source-ref + message
        val body = mergeReq.body.readUtf8()
        assertTrue(body.contains("\"strategyId\":\"no-ff\""), "Body must carry strategyId; got: $body")
        assertTrue(body.contains("\"deleteSourceRef\":true"), "Body must carry deleteSourceRef; got: $body")
        assertTrue(body.contains("\"message\":\"Merge PR #42\""), "Body must carry commit message; got: $body")
    }

    @Test
    fun `mergePullRequestWithRetry retries once on 409 then succeeds`() = runTest {
        // GET v3 → POST /merge → 409 → refetch v5 → POST /merge → 200
        server.enqueue(MockResponse().setResponseCode(200).setBody(basePrV3))
        server.enqueue(
            MockResponse().setResponseCode(409)
                .setBody("""{"errors":[{"message":"version conflict"}]}""")
        )
        val refetched = basePrV3.replace("\"version\": 3", "\"version\": 5")
        server.enqueue(MockResponse().setResponseCode(200).setBody(refetched))
        server.enqueue(MockResponse().setResponseCode(200).setBody(mergedPr))

        val result = client.mergePullRequestWithRetry(
            repo = RepoCoords("PROJ", "repo"),
            prId = 42,
        )

        assertTrue(result is ApiResult.Success, "Expected success after retry; got: $result")
        assertEquals(4, server.requestCount, "Expected GET, POST, GET, POST = 4 requests")

        // First attempt's merge query carries version=3
        server.takeRequest() // GET 1
        val firstMerge = server.takeRequest()
        assertTrue(
            firstMerge.path!!.contains("version=3"),
            "First merge attempt must use fetched version 3, got ${firstMerge.path}"
        )
        server.takeRequest() // GET 2 (refetch)
        val secondMerge = server.takeRequest()
        assertTrue(
            secondMerge.path!!.contains("version=5"),
            "Retry merge must use refetched version 5, got ${secondMerge.path}"
        )
    }

    @Test
    fun `mergePullRequestWithRetry returns STALE_VERSION after second 409`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(basePrV3))
        server.enqueue(
            MockResponse().setResponseCode(409)
                .setBody("""{"errors":[{"message":"version conflict"}]}""")
        )
        val refetched = basePrV3.replace("\"version\": 3", "\"version\": 5")
        server.enqueue(MockResponse().setResponseCode(200).setBody(refetched))
        server.enqueue(
            MockResponse().setResponseCode(409)
                .setBody("""{"errors":[{"message":"version conflict"}]}""")
        )

        val result = client.mergePullRequestWithRetry(
            repo = RepoCoords("PROJ", "repo"),
            prId = 42,
        )

        assertTrue(result is ApiResult.Error, "Expected STALE_VERSION; got: $result")
        val error = result as ApiResult.Error
        assertEquals(ErrorType.STALE_VERSION, error.type)
        assertTrue(
            error.message.contains("refresh", ignoreCase = true) ||
                error.message.contains("updated by someone else", ignoreCase = true),
            "Expected actionable copy; got: ${error.message}"
        )
        assertEquals(4, server.requestCount, "Exactly 2 GET+POST attempts before giving up")
    }

    @Test
    fun `mergePullRequestWithRetry propagates non-409 errors without retry`() = runTest {
        // GET succeeds, merge POST fails with 401 — modifyPullRequest-style retry
        // must not fire. 401 is not in OkHttp's RetryInterceptor's retriable set so a
        // single response is enough to verify no extra GET-and-POST happens.
        server.enqueue(MockResponse().setResponseCode(200).setBody(basePrV3))
        server.enqueue(MockResponse().setResponseCode(401).setBody("Unauthorized"))

        val result = client.mergePullRequestWithRetry(
            repo = RepoCoords("PROJ", "repo"),
            prId = 42,
        )

        assertTrue(result is ApiResult.Error)
        assertEquals(ErrorType.AUTH_FAILED, (result as ApiResult.Error).type)
        assertEquals(2, server.requestCount, "No retry on non-409 errors")
    }

    @Test
    fun `mergePullRequestWithRetry propagates GET failure without attempting merge`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))

        val result = client.mergePullRequestWithRetry(
            repo = RepoCoords("PROJ", "repo"),
            prId = 42,
        )

        assertTrue(result is ApiResult.Error)
        assertEquals(ErrorType.NOT_FOUND, (result as ApiResult.Error).type)
        assertEquals(1, server.requestCount, "Merge POST must not fire when GET fails")
    }
}

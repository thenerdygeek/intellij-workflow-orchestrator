package com.workflow.orchestrator.pullrequest.service

import com.workflow.orchestrator.core.bitbucket.BitbucketBranchClient
import com.workflow.orchestrator.core.bitbucket.RepoCoords
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
 * End-to-end retry tests for the four `PrActionService` mutating ops fixed in
 * PR 3 of the 2026-05-07 write-ops fix plan. Each op is exercised through
 * [BitbucketBranchClient.modifyPullRequest] with the real mutator
 * ([updateTitleMutator] / [addReviewerMutator] / [removeReviewerMutator]) and
 * a [MockWebServer] that replays the canonical 409-once-then-success sequence:
 *
 *  1. GET PR → version N
 *  2. PUT (with version N) → 409
 *  3. GET PR → version N+2 (someone else bumped it twice)
 *  4. PUT (with version N+2) → 200
 *
 *  For merge, the equivalent path uses
 *  [BitbucketBranchClient.mergePullRequestWithRetry] (POST not PUT).
 *
 * The PrActionService class itself is project-scoped (`@Service(Service.Level.PROJECT)`)
 * and not directly instantiable in JVM unit tests — its retry-success contract is
 * therefore proven at the (mutator + helper) seam, which is the path the service
 * walks in production.
 */
class PrActionServiceModifyRetryTest {

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

    private fun prJson(version: Int, title: String = "Original", reviewers: List<String> = listOf("alice")) =
        """{
            "id": 42,
            "title": "$title",
            "description": "desc",
            "state": "OPEN",
            "version": $version,
            "reviewers": [${reviewers.joinToString(",") { """{"user":{"name":"$it"}}""" }}],
            "links": {"self": [{"href": "https://example/pr/42"}]}
        }""".trimIndent()

    private fun enqueue409Then200(getV1: String, getV2: String, putOk: String) {
        server.enqueue(MockResponse().setResponseCode(200).setBody(getV1))
        server.enqueue(
            MockResponse().setResponseCode(409)
                .setBody("""{"errors":[{"message":"version conflict"}]}""")
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody(getV2))
        server.enqueue(MockResponse().setResponseCode(200).setBody(putOk))
    }

    // ---------------- updateTitle ----------------

    @Test
    fun `updateTitle 409 then success retries with refreshed version`() = runTest {
        enqueue409Then200(
            getV1 = prJson(version = 3),
            getV2 = prJson(version = 5),
            putOk = prJson(version = 6, title = "New title"),
        )

        val result = client.modifyPullRequest(RepoCoords("P", "r"), prId = 42) { current ->
            updateTitleMutator(current, "New title")
        }

        assertTrue(result is ApiResult.Success, "Expected success after retry; got: $result")
        assertEquals(6, (result as ApiResult.Success).data.version)

        // Inspect the two PUT bodies — first carried v3, second v5
        server.takeRequest()  // GET 1
        val put1 = server.takeRequest()
        assertEquals("PUT", put1.method)
        assertTrue(put1.body.readUtf8().contains("\"version\":3"), "First PUT must carry v3")

        server.takeRequest()  // GET 2 (refetch)
        val put2 = server.takeRequest()
        assertEquals("PUT", put2.method)
        assertTrue(put2.body.readUtf8().contains("\"version\":5"), "Retry PUT must carry refreshed v5")
    }

    @Test
    fun `updateTitle double 409 surfaces STALE_VERSION typed error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(prJson(version = 3)))
        server.enqueue(
            MockResponse().setResponseCode(409)
                .setBody("""{"errors":[{"message":"version conflict"}]}""")
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody(prJson(version = 5)))
        server.enqueue(
            MockResponse().setResponseCode(409)
                .setBody("""{"errors":[{"message":"version conflict"}]}""")
        )

        val result = client.modifyPullRequest(RepoCoords("P", "r"), prId = 42) { current ->
            updateTitleMutator(current, "New title")
        }

        assertTrue(result is ApiResult.Error)
        assertEquals(ErrorType.STALE_VERSION, (result as ApiResult.Error).type)
    }

    // ---------------- addReviewer ----------------

    @Test
    fun `addReviewer 409 then success retries with refreshed version`() = runTest {
        enqueue409Then200(
            getV1 = prJson(version = 3, reviewers = listOf("alice")),
            getV2 = prJson(version = 5, reviewers = listOf("alice", "dave")),  // someone added dave
            putOk = prJson(version = 6, reviewers = listOf("alice", "dave", "carol")),
        )

        val result = client.modifyPullRequest(RepoCoords("P", "r"), prId = 42) { current ->
            addReviewerMutator(current, "carol")
        }

        assertTrue(result is ApiResult.Success, "Expected success after retry; got: $result")
        assertEquals(6, (result as ApiResult.Success).data.version)

        server.takeRequest()  // GET 1
        server.takeRequest()  // PUT 1 → 409
        server.takeRequest()  // GET 2 (refetch picks up dave)
        val put2 = server.takeRequest()
        val body = put2.body.readUtf8()
        assertTrue(body.contains("\"version\":5"), "Retry PUT must carry refreshed v5")
        // Retry mutator preserves dave (added by other caller) AND adds carol.
        assertTrue(body.contains("alice"), "Retry must preserve alice")
        assertTrue(body.contains("dave"), "Retry must preserve concurrent-add dave")
        assertTrue(body.contains("carol"), "Retry must add carol")
    }

    @Test
    fun `addReviewer double 409 surfaces STALE_VERSION typed error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(prJson(version = 3)))
        server.enqueue(
            MockResponse().setResponseCode(409)
                .setBody("""{"errors":[{"message":"version conflict"}]}""")
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody(prJson(version = 5)))
        server.enqueue(
            MockResponse().setResponseCode(409)
                .setBody("""{"errors":[{"message":"version conflict"}]}""")
        )

        val result = client.modifyPullRequest(RepoCoords("P", "r"), prId = 42) { current ->
            addReviewerMutator(current, "carol")
        }

        assertEquals(ErrorType.STALE_VERSION, (result as ApiResult.Error).type)
    }

    // ---------------- removeReviewer ----------------

    @Test
    fun `removeReviewer 409 then success retries with refreshed version`() = runTest {
        enqueue409Then200(
            getV1 = prJson(version = 3, reviewers = listOf("alice", "bob")),
            getV2 = prJson(version = 5, reviewers = listOf("alice", "bob", "dave")),
            putOk = prJson(version = 6, reviewers = listOf("alice", "dave")),
        )

        val result = client.modifyPullRequest(RepoCoords("P", "r"), prId = 42) { current ->
            removeReviewerMutator(current, "bob")
        }

        assertTrue(result is ApiResult.Success, "Expected success after retry; got: $result")

        server.takeRequest()  // GET 1
        server.takeRequest()  // PUT 1 → 409
        server.takeRequest()  // GET 2
        val put2 = server.takeRequest()
        val body = put2.body.readUtf8()
        assertTrue(body.contains("\"version\":5"), "Retry PUT must carry refreshed v5")
        // Retry must remove bob but preserve dave (added concurrently)
        assertTrue(!body.contains("bob"), "Retry must drop bob")
        assertTrue(body.contains("dave"), "Retry must preserve concurrent-add dave")
        assertTrue(body.contains("alice"), "Retry must preserve alice")
    }

    @Test
    fun `removeReviewer double 409 surfaces STALE_VERSION typed error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(prJson(version = 3)))
        server.enqueue(
            MockResponse().setResponseCode(409)
                .setBody("""{"errors":[{"message":"version conflict"}]}""")
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody(prJson(version = 5)))
        server.enqueue(
            MockResponse().setResponseCode(409)
                .setBody("""{"errors":[{"message":"version conflict"}]}""")
        )

        val result = client.modifyPullRequest(RepoCoords("P", "r"), prId = 42) { current ->
            removeReviewerMutator(current, "bob")
        }

        assertEquals(ErrorType.STALE_VERSION, (result as ApiResult.Error).type)
    }
}

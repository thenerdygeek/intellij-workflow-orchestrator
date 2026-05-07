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
 * Tests for [BitbucketBranchClient.modifyPullRequest] — the fetch-modify-write
 * helper introduced by PR 1 of the 2026-05-07 write-ops fix plan.
 *
 * The helper must:
 *  - GET the PR, run mutator with current state, PUT the result.
 *  - On 409, refetch and retry once.
 *  - On second 409, return [ErrorType.STALE_VERSION].
 */
class BitbucketBranchClientModifyPullRequestTest {

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

    private val basePrJson = """{
        "id": 42,
        "title": "Original title",
        "description": "Original description",
        "state": "OPEN",
        "version": 3,
        "links": {"self": [{"href": "https://example/pr/42"}]}
    }""".trimIndent()

    private val updatedPrJson = """{
        "id": 42,
        "title": "New title",
        "description": "Original description",
        "state": "OPEN",
        "version": 4,
        "links": {"self": [{"href": "https://example/pr/42"}]}
    }""".trimIndent()

    @Test
    fun `modifyPullRequest succeeds on first try when version is current`() = runTest {
        // GET /pull-requests/42 → version 3
        server.enqueue(MockResponse().setResponseCode(200).setBody(basePrJson))
        // PUT /pull-requests/42 → 200 with new version
        server.enqueue(MockResponse().setResponseCode(200).setBody(updatedPrJson))

        var mutatorCalls = 0
        val result = client.modifyPullRequest(
            repo = RepoCoords("PROJ", "repo"),
            prId = 42,
        ) { current ->
            mutatorCalls++
            BitbucketPrUpdateRequest(
                title = "New title",
                description = current.description.orEmpty(),
                version = current.version,
            )
        }

        assertTrue(result is ApiResult.Success, "Expected success; got: $result")
        assertEquals(4, (result as ApiResult.Success).data.version)
        assertEquals(1, mutatorCalls, "Mutator should be called exactly once")

        // Verify the GET and the PUT both fired.
        val getReq = server.takeRequest()
        assertEquals("GET", getReq.method)
        val putReq = server.takeRequest()
        assertEquals("PUT", putReq.method)
        // Verify the PUT body carried the fetched version.
        val body = putReq.body.readUtf8()
        assertTrue(body.contains("\"version\":3"), "PUT body must carry fetched version 3, got: $body")
    }

    @Test
    fun `modifyPullRequest retries once on 409 then succeeds`() = runTest {
        // First GET → version 3, first PUT → 409
        server.enqueue(MockResponse().setResponseCode(200).setBody(basePrJson))
        server.enqueue(
            MockResponse().setResponseCode(409)
                .setBody("""{"errors":[{"message":"version conflict"}]}""")
        )
        // Refetch GET → version 5 (someone else bumped it), second PUT → 200
        val refetched = basePrJson.replace("\"version\": 3", "\"version\": 5")
        server.enqueue(MockResponse().setResponseCode(200).setBody(refetched))
        val finalUpdate = updatedPrJson.replace("\"version\": 4", "\"version\": 6")
        server.enqueue(MockResponse().setResponseCode(200).setBody(finalUpdate))

        var mutatorCalls = 0
        val versionsSeen = mutableListOf<Int>()
        val result = client.modifyPullRequest(
            repo = RepoCoords("PROJ", "repo"),
            prId = 42,
        ) { current ->
            mutatorCalls++
            versionsSeen.add(current.version)
            BitbucketPrUpdateRequest(
                title = "New title",
                description = current.description.orEmpty(),
                version = current.version,
            )
        }

        assertTrue(result is ApiResult.Success, "Expected success after retry; got: $result")
        assertEquals(6, (result as ApiResult.Success).data.version)
        assertEquals(2, mutatorCalls, "Mutator should be called twice (initial + retry)")
        assertEquals(listOf(3, 5), versionsSeen, "Mutator should see fresh version on retry")
        assertEquals(4, server.requestCount, "Expected GET, PUT, GET, PUT = 4 requests")
    }

    @Test
    fun `modifyPullRequest gives up after second 409 with STALE_VERSION error`() = runTest {
        // GET → 3, PUT → 409, GET → 5, PUT → 409 again.
        server.enqueue(MockResponse().setResponseCode(200).setBody(basePrJson))
        server.enqueue(
            MockResponse().setResponseCode(409)
                .setBody("""{"errors":[{"message":"version conflict"}]}""")
        )
        val refetched = basePrJson.replace("\"version\": 3", "\"version\": 5")
        server.enqueue(MockResponse().setResponseCode(200).setBody(refetched))
        server.enqueue(
            MockResponse().setResponseCode(409)
                .setBody("""{"errors":[{"message":"version conflict"}]}""")
        )

        val result = client.modifyPullRequest(
            repo = RepoCoords("PROJ", "repo"),
            prId = 42,
        ) { current ->
            BitbucketPrUpdateRequest(
                title = "New title",
                description = current.description.orEmpty(),
                version = current.version,
            )
        }

        assertTrue(result is ApiResult.Error, "Expected STALE_VERSION error; got: $result")
        val error = result as ApiResult.Error
        assertEquals(ErrorType.STALE_VERSION, error.type)
        assertTrue(
            error.message.contains("refresh", ignoreCase = true) ||
                error.message.contains("updated by someone else", ignoreCase = true),
            "Expected actionable stale-version copy; got: ${error.message}"
        )
        assertEquals(4, server.requestCount, "Expected exactly 2 attempts (GET+PUT each) before giving up")
    }

    @Test
    fun `modifyPullRequest propagates non-409 errors without modifyPullRequest-level retry`() = runTest {
        // GET succeeds, PUT 401 — must NOT trigger the modifyPullRequest stale-version retry.
        // 401 is not in the OkHttp RetryInterceptor's retriable set (429+5xx) so a single
        // PUT response is enough to assert no extra modifyPullRequest fetch happens.
        server.enqueue(MockResponse().setResponseCode(200).setBody(basePrJson))
        server.enqueue(MockResponse().setResponseCode(401).setBody("Unauthorized"))

        val result = client.modifyPullRequest(
            repo = RepoCoords("PROJ", "repo"),
            prId = 42,
        ) { current ->
            BitbucketPrUpdateRequest(
                title = "x",
                description = "y",
                version = current.version,
            )
        }

        assertTrue(result is ApiResult.Error)
        assertEquals(ErrorType.AUTH_FAILED, (result as ApiResult.Error).type)
        // GET, PUT — exactly 2 requests. No second fetch-and-put because the error
        // is not STALE_VERSION.
        assertEquals(2, server.requestCount, "Must not perform stale-version retry on non-409 errors")
    }

    @Test
    fun `modifyPullRequest propagates GET failure without attempting PUT`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))

        val result = client.modifyPullRequest(
            repo = RepoCoords("PROJ", "repo"),
            prId = 42,
        ) { current ->
            BitbucketPrUpdateRequest(
                title = "x",
                description = "y",
                version = current.version,
            )
        }

        assertTrue(result is ApiResult.Error)
        assertEquals(ErrorType.NOT_FOUND, (result as ApiResult.Error).type)
        assertEquals(1, server.requestCount, "PUT must not fire when GET fails")
    }
}

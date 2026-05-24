package com.workflow.orchestrator.pullrequest.service

import com.workflow.orchestrator.core.bitbucket.BitbucketBranchClient
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
 * Regression tests for audit finding pullrequest:F-4 — PR Decline 409 version race.
 *
 * PrActionService.decline() previously used the stale version from the UI cache.
 * If the PR was updated concurrently the POST returned 409 and the decline silently
 * failed with no user-facing error.
 *
 * The fix always fetches the latest version before posting decline, and PrDetailPanel
 * now surfaces ApiResult.Error to the user instead of silently swallowing it.
 * Tests exercise the contract at the BitbucketBranchClient seam using MockWebServer.
 */
class PrActionServiceDeclineRetryTest {

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

    private fun prJson(version: Int, state: String = "OPEN") =
        """{
            "id": 42,
            "title": "My PR",
            "description": "",
            "state": "$state",
            "version": $version,
            "reviewers": [],
            "links": {"self": [{"href": "https://example/pr/42"}]}
        }""".trimIndent()

    // --- declinePullRequest carries version in query string ---

    @Test
    fun `declinePullRequest sends version in query string`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(prJson(version = 7, state = "DECLINED")))

        val result = client.declinePullRequest("P", "r", prId = 42, version = 7)

        assertTrue(result is ApiResult.Success, "Expected success; got: $result")
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertTrue(
            recorded.path!!.contains("version=7"),
            "Decline POST must carry version=7 in query string; path=${recorded.path}"
        )
    }

    @Test
    fun `declinePullRequest maps 409 to VALIDATION_ERROR with version-conflict message`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(409)
                .setBody("""{"errors":[{"message":"version conflict — refresh and retry"}]}""")
        )

        val result = client.declinePullRequest("P", "r", prId = 42, version = 3)

        assertTrue(result is ApiResult.Error, "409 must produce an error; got: $result")
        assertEquals(ErrorType.VALIDATION_ERROR, (result as ApiResult.Error).type)
        assertTrue(
            result.message.contains("version conflict", ignoreCase = true),
            "Error message must mention 'version conflict'; got: ${result.message}"
        )
    }

    // --- getPullRequestDetail supplies the fresh version ---

    @Test
    fun `getPullRequestDetail returns current version from server`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(prJson(version = 9)))

        val result = client.getPullRequestDetail("P", "r", prId = 42)

        assertTrue(result is ApiResult.Success, "Expected success; got: $result")
        assertEquals(9, (result as ApiResult.Success).data.version)
    }

    // --- fetchAndDecline pattern: GET then POST with fresh version ---

    @Test
    fun `fetchAndDecline pattern succeeds when GET then POST returns 200`() = runTest {
        // GET returns version 5
        server.enqueue(MockResponse().setResponseCode(200).setBody(prJson(version = 5)))
        // POST decline with version 5 → success
        server.enqueue(MockResponse().setResponseCode(200).setBody(prJson(version = 6, state = "DECLINED")))

        val getResult = client.getPullRequestDetail("P", "r", 42)
        val freshVersion = (getResult as ApiResult.Success).data.version
        val declineResult = client.declinePullRequest("P", "r", 42, freshVersion)

        assertTrue(declineResult is ApiResult.Success, "Expected success after GET→POST; got: $declineResult")

        val getReq = server.takeRequest()
        assertEquals("GET", getReq.method)
        val postReq = server.takeRequest()
        assertEquals("POST", postReq.method)
        assertTrue(
            postReq.path!!.contains("version=5"),
            "POST must use the freshly-fetched version=5; path=${postReq.path}"
        )
    }

    @Test
    fun `declinePullRequest 409 then success in two-step sequence`() = runTest {
        // Simulate the retry cycle: POST v3 → 409, POST v5 → 200
        server.enqueue(
            MockResponse().setResponseCode(409)
                .setBody("""{"errors":[{"message":"version conflict — refresh and retry"}]}""")
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody(prJson(version = 6, state = "DECLINED")))

        val first = client.declinePullRequest("P", "r", 42, version = 3)
        assertTrue(first is ApiResult.Error)
        assertEquals(ErrorType.VALIDATION_ERROR, (first as ApiResult.Error).type)
        assertTrue(first.message.contains("version conflict", ignoreCase = true))

        // Retry with updated version
        val second = client.declinePullRequest("P", "r", 42, version = 5)
        assertTrue(second is ApiResult.Success, "Retry with updated version must succeed; got: $second")

        // Verify each POST carried its respective version
        val req1 = server.takeRequest()
        assertEquals("POST", req1.method)
        assertTrue(req1.path!!.contains("version=3"), "First POST must carry v3; path=${req1.path}")
        val req2 = server.takeRequest()
        assertEquals("POST", req2.method)
        assertTrue(req2.path!!.contains("version=5"), "Retry POST must carry v5; path=${req2.path}")
    }
}

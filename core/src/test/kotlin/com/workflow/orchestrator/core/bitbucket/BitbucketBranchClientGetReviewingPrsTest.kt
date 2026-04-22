package com.workflow.orchestrator.core.bitbucket

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Regression guards for the get_reviewing_prs username threading bug (F-HIGH).
 *
 * These tests verify the CLIENT-layer: BitbucketBranchClient correctly
 * threads the `username` parameter into the URL as `username.1=<value>`
 * only when non-null/non-blank, and omits it when null/blank.
 * The role is hardcoded as REVIEWER in the client URL.
 *
 * The service-layer fix (resolveCurrentUsername) is verified by code inspection
 * and end-to-end via Phase-2 integration; the service cannot be unit-tested
 * directly because BitbucketBranchClientCache.get() requires the IntelliJ platform.
 */
class BitbucketBranchClientGetReviewingPrsTest {
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

    @Test
    fun `getReviewingPullRequests threads username into URL as username_1`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"values":[],"isLastPage":true}"""
        ))
        client.getReviewingPullRequests("P", "R", username = "alice")
        val req = server.takeRequest()
        val path = req.path ?: ""
        assertTrue(path.contains("username.1=alice"), "URL should contain username.1=alice, got: $path")
        assertTrue(path.contains("role.1=REVIEWER"), "URL should contain role.1=REVIEWER, got: $path")
    }

    @Test
    fun `getReviewingPullRequests with null username omits username_1 param`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"values":[],"isLastPage":true}"""
        ))
        client.getReviewingPullRequests("P", "R", username = null)
        val req = server.takeRequest()
        val path = req.path ?: ""
        assertTrue(!path.contains("username.1"), "URL should omit username.1 when null, got: $path")
    }

    @Test
    fun `getReviewingPullRequests with blank username omits username_1 param`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"values":[],"isLastPage":true}"""
        ))
        client.getReviewingPullRequests("P", "R", username = "")
        val req = server.takeRequest()
        val path = req.path ?: ""
        assertTrue(!path.contains("username.1"), "URL should omit username.1 when blank, got: $path")
    }
}

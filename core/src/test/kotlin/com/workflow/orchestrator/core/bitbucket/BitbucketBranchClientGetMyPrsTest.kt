package com.workflow.orchestrator.core.bitbucket

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Regression guards for the get_my_prs username threading bug (F-HIGH).
 *
 * These tests verify the CLIENT-layer: BitbucketBranchClient correctly
 * threads the `username` parameter into the URL as `username.1=<value>`
 * only when non-null/non-blank, and omits it when null.
 *
 * The service-layer fix (resolveCurrentUsername) is verified by code inspection
 * and end-to-end via Phase-2 integration; the service cannot be unit-tested
 * directly because BitbucketBranchClientCache.get() requires the IntelliJ platform.
 */
class BitbucketBranchClientGetMyPrsTest {
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
    fun `getMyPullRequests threads username into URL as username_1`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"values":[],"isLastPage":true}"""
            )
        )
        client.getMyPullRequests("P", "R", username = "alice", state = "OPEN")
        val req = server.takeRequest()
        val path = req.path ?: ""
        assertTrue(path.contains("username.1=alice"), "URL should contain username.1=alice, got: $path")
        assertTrue(path.contains("role.1=AUTHOR"), "URL should contain role.1=AUTHOR, got: $path")
    }

    @Test
    fun `getMyPullRequests with null username omits username_1 param`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"values":[],"isLastPage":true}"""
            )
        )
        client.getMyPullRequests("P", "R", username = null, state = "OPEN")
        val req = server.takeRequest()
        val path = req.path ?: ""
        assertTrue(!path.contains("username.1"), "URL should omit username.1 when null, got: $path")
    }

    @Test
    fun `getMyPullRequests with blank username omits username_1 param`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"values":[],"isLastPage":true}"""
            )
        )
        client.getMyPullRequests("P", "R", username = "", state = "OPEN")
        val req = server.takeRequest()
        val path = req.path ?: ""
        assertTrue(!path.contains("username.1"), "URL should omit username.1 when blank, got: $path")
    }
}

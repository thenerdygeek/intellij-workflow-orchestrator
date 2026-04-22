package com.workflow.orchestrator.core.bitbucket

import com.workflow.orchestrator.core.model.ApiResult
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

class BitbucketBranchClientDiffSizeCapTest {
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
    fun `getPullRequestDiff truncates above MAX_DIFF_CHARS with marker`() = runTest {
        val largeDiff = "x".repeat(400_000)  // 400K chars, above the 327,680 cap
        server.enqueue(MockResponse().setResponseCode(200).setBody(largeDiff))
        val result = client.getPullRequestDiff("P", "R", 1)
        assertTrue(result is ApiResult.Success, "expected Success, got $result")
        val text = (result as ApiResult.Success).data
        // allow room for the truncation marker
        assertTrue(
            text.length in 327_681..327_880,
            "diff was ${text.length} chars; expected ~327,680 + short marker"
        )
        assertTrue(
            text.contains("[... diff truncated at 327680 chars ...]"),
            "truncation marker missing"
        )
    }

    @Test
    fun `getPullRequestDiff returns full diff below cap`() = runTest {
        val smallDiff = "x".repeat(1000)
        server.enqueue(MockResponse().setResponseCode(200).setBody(smallDiff))
        val result = client.getPullRequestDiff("P", "R", 1)
        assertTrue(result is ApiResult.Success, "expected Success, got $result")
        val text = (result as ApiResult.Success).data
        assertEquals(1000, text.length)
        assertTrue(!text.contains("truncated"), "should not contain truncation marker for small diff")
    }
}

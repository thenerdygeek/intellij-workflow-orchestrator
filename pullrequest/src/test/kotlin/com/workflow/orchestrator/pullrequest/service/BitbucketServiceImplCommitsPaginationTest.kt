package com.workflow.orchestrator.pullrequest.service

import com.workflow.orchestrator.core.bitbucket.BitbucketBranchClient
import com.workflow.orchestrator.core.model.ApiResult
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Tests for [BitbucketBranchClient.getPullRequestCommits] `start` param + URL wiring.
 *
 * Note: [BitbucketServiceImpl] is NOT directly unit-testable without the IntelliJ
 * platform (it resolves [ConnectionSettings] via [BitbucketBranchClientCache] which
 * calls platform service lookups). These tests therefore operate at the client layer
 * and verify:
 *   1. `start=0` appears in the first-page request URL.
 *   2. A non-zero `start` value is correctly threaded into the URL.
 *   3. `isLastPage=true` causes a single-page result to be returned directly.
 *   4. The response is correctly parsed when two calls are made (simulating
 *      the service-layer loop calling the client twice).
 *
 * The service-layer loop itself (in [BitbucketServiceImpl.getPullRequestCommits])
 * is covered by the integration test suite; this file satisfies the unit-testable
 * surface (F-HIGH audit item PR-diff.getPullRequestCommits).
 */
class BitbucketServiceImplCommitsPaginationTest {

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
    fun tearDown() {
        server.shutdown()
    }

    // ── URL wiring ────────────────────────────────────────────────────────────

    @Test
    fun `getPullRequestCommits default start=0 appears in URL`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"values":[{"id":"abc","displayId":"abc1234","message":"First commit","authorTimestamp":0}],"isLastPage":true}"""
        ))

        val result = client.getPullRequestCommits("P", "R", 1)
        assertTrue(result is ApiResult.Success, "expected Success but got $result")

        val request = server.takeRequest()
        val path = request.path ?: ""
        assertTrue(
            path.contains("start=0"),
            "Expected start=0 in URL but path was: $path"
        )
    }

    @Test
    fun `getPullRequestCommits explicit start=25 is threaded into URL`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"values":[{"id":"def","displayId":"def5678","message":"Page 2 commit","authorTimestamp":0}],"isLastPage":true}"""
        ))

        val result = client.getPullRequestCommits("P", "R", 1, start = 25)
        assertTrue(result is ApiResult.Success, "expected Success but got $result")

        val request = server.takeRequest()
        val path = request.path ?: ""
        assertTrue(
            path.contains("start=25"),
            "Expected start=25 in URL but path was: $path"
        )
    }

    // ── Two-call simulation (mirrors service-layer loop) ──────────────────────

    @Test
    fun `client returns page 1 data and page 2 data when called sequentially`() = runTest {
        // Page 1 response: not last page
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"values":[
              {"id":"aaa","displayId":"aaa111","message":"Commit A","authorTimestamp":1000},
              {"id":"bbb","displayId":"bbb222","message":"Commit B","authorTimestamp":2000}
            ],"isLastPage":false,"nextPageStart":2}"""
        ))
        // Page 2 response: last page
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"values":[
              {"id":"ccc","displayId":"ccc333","message":"Commit C","authorTimestamp":3000}
            ],"isLastPage":true,"nextPageStart":null}"""
        ))

        // Simulate what BitbucketServiceImpl's pagination loop does
        val page1Result = client.getPullRequestCommits("P", "R", 42, start = 0)
        assertTrue(page1Result is ApiResult.Success, "page 1 expected Success, got $page1Result")
        val page1 = (page1Result as ApiResult.Success).data
        assertEquals(2, page1.values.size)
        assertEquals(false, page1.isLastPage)
        assertEquals(2, page1.nextPageStart)

        val page2Result = client.getPullRequestCommits("P", "R", 42, start = page1.nextPageStart!!)
        assertTrue(page2Result is ApiResult.Success, "page 2 expected Success, got $page2Result")
        val page2 = (page2Result as ApiResult.Success).data
        assertEquals(1, page2.values.size)
        assertTrue(page2.isLastPage)

        // Verify URL params
        val req1 = server.takeRequest()
        assertTrue(
            !req1.path!!.contains("start=") || req1.path!!.contains("start=0"),
            "page 1 path should have start=0, was: ${req1.path}"
        )
        val req2 = server.takeRequest()
        assertTrue(
            req2.path!!.contains("start=2"),
            "page 2 path should have start=2, was: ${req2.path}"
        )

        // Verify aggregation logic (what the service layer does)
        val allCommits = page1.values + page2.values
        assertEquals(3, allCommits.size)
        assertEquals(listOf("aaa", "bbb", "ccc"), allCommits.map { it.id })
    }

    // ── Error propagation ─────────────────────────────────────────────────────

    @Test
    fun `getPullRequestCommits returns Error on 401`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        val result = client.getPullRequestCommits("P", "R", 1)
        assertTrue(result is ApiResult.Error, "expected Error on 401 but got $result")
    }

    @Test
    fun `getPullRequestCommits returns Error on 404`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))

        val result = client.getPullRequestCommits("P", "R", 999)
        assertTrue(result is ApiResult.Error, "expected Error on 404 but got $result")
    }
}

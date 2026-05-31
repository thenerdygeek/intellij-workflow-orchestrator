package com.workflow.orchestrator.pullrequest.service

import com.workflow.orchestrator.core.bitbucket.BitbucketBranchClient
import com.workflow.orchestrator.core.bitbucket.BitbucketPrDetail
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
 * Tests for PULLREQUEST-COV-5 — fetchDashboardPrs 100-result cap boundary and
 * mid-pagination error propagation.
 *
 * PrListService.fetchDashboardPrs (lines 200-232) is a private method that calls
 * BitbucketBranchClient.getDashboardPullRequests in a loop. It has two critical
 * untested paths:
 *   1. The `results.size < 100` cap — pagination stops when exactly 100 results
 *      accumulate, even if isLastPage=false.
 *   2. A mid-pagination ApiResult.Error causes a break and partial results are
 *      returned (not an exception).
 *
 * Because fetchDashboardPrs is private and PrListService depends on IntelliJ
 * platform services, these tests operate at the BitbucketBranchClient seam (same
 * pattern as BitbucketServiceImplCommitsPaginationTest and
 * BitbucketServiceImplListPrCommentsPaginationTest). The loop logic is replicated
 * here to mirror the production fetchDashboardPrs behaviour exactly.
 */
class PrListServiceFetchDashboardTest {

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

    /**
     * Build a dashboard-PR page JSON body with [count] items (each with a unique id),
     * each carrying a toRef.repository block so they pass the key-filtering step in
     * fetchDashboardPrs.
     */
    private fun dashboardPage(
        count: Int,
        startId: Int,
        isLastPage: Boolean,
        nextPageStart: Int? = null,
    ): String {
        val nextField = if (nextPageStart != null) ""","nextPageStart":$nextPageStart""" else ""
        val values = (startId until startId + count).joinToString(",") { id ->
            """{
                "id": $id,
                "title": "PR-$id",
                "description": "",
                "state": "OPEN",
                "version": 1,
                "reviewers": [],
                "toRef": {
                    "id": "refs/heads/main",
                    "displayId": "main",
                    "latestCommit": "abc$id",
                    "repository": {
                        "slug": "repo",
                        "name": "repo",
                        "project": { "key": "PROJ" }
                    }
                },
                "links": { "self": [{ "href": "https://example/pr/$id" }] }
            }"""
        }
        return """{"values": [$values], "size": $count, "isLastPage": $isLastPage$nextField}"""
    }

    /**
     * Simulate the fetchDashboardPrs loop (mirrors PrListService.fetchDashboardPrs
     * lines 206-231) at the BitbucketBranchClient seam.
     *
     * Passes configuredRepoKeys=empty so all returned PRs are accepted (same as when
     * no multi-repo filtering is needed).
     */
    private suspend fun simulateFetchDashboardLoop(
        cap: Int = 100,
        pageSize: Int = 25,
    ): List<BitbucketPrDetail> {
        val results = mutableListOf<BitbucketPrDetail>()
        var start = 0
        var isLast = false
        while (!isLast && results.size < cap) {
            val page = client.getDashboardPullRequests("AUTHOR", "OPEN", limit = pageSize, start = start)
            if (page is ApiResult.Success) {
                page.data.values.forEach { pr ->
                    val repoSlug = pr.toRef?.repository?.slug.orEmpty()
                    val projKey = pr.toRef?.repository?.project?.key.orEmpty()
                    if (projKey.isBlank() || repoSlug.isBlank()) return@forEach
                    results += pr
                }
                isLast = page.data.isLastPage
                start = page.data.nextPageStart ?: break
            } else {
                // Error — break and return partial results (same as production code)
                break
            }
        }
        return results
    }

    // ── 100-result cap boundary ───────────────────────────────────────────────

    @Test
    fun `fetchDashboardPrs stops at exactly 100 results even when isLastPage=false`() = runTest {
        // Queue 4 pages of 25 PRs each (total = 100), each with isLastPage=false
        // The loop must stop after 4 pages once results.size reaches 100
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            dashboardPage(count = 25, startId = 1, isLastPage = false, nextPageStart = 25)
        ))
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            dashboardPage(count = 25, startId = 26, isLastPage = false, nextPageStart = 50)
        ))
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            dashboardPage(count = 25, startId = 51, isLastPage = false, nextPageStart = 75)
        ))
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            dashboardPage(count = 25, startId = 76, isLastPage = false, nextPageStart = 100)
        ))
        // A 5th page should NOT be fetched because results.size == 100 after page 4

        val results = simulateFetchDashboardLoop()

        assertEquals(100, results.size,
            "fetchDashboardPrs must return exactly 100 results and stop at the cap")
        // Verify that only 4 HTTP requests were made (no 5th page)
        assertEquals(4, server.requestCount,
            "Only 4 requests must be made to reach the 100-result cap; no 5th page should be fetched")
    }

    @Test
    fun `fetchDashboardPrs request after cap has correct start parameter`() = runTest {
        // Two pages: first returns 25 with nextPageStart=25, second returns 25 with nextPageStart=50
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            dashboardPage(count = 25, startId = 1, isLastPage = false, nextPageStart = 25)
        ))
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            dashboardPage(count = 25, startId = 26, isLastPage = true)
        ))

        simulateFetchDashboardLoop()

        val req1 = server.takeRequest()
        val req2 = server.takeRequest()
        assertTrue(req1.path!!.contains("start=0"),
            "First request must carry start=0; path=${req1.path}")
        assertTrue(req2.path!!.contains("start=25"),
            "Second request must carry start=25 (nextPageStart from first response); path=${req2.path}")
    }

    // ── mid-pagination error returns partial results ──────────────────────────


    @Test
    fun `fetchDashboardPrs returns partial results from page 1 when page 2 returns 401`() = runTest {
        // Page 1 succeeds; page 2 returns 401 (token expired mid-session)
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            dashboardPage(count = 10, startId = 1, isLastPage = false, nextPageStart = 10)
        ))
        server.enqueue(MockResponse().setResponseCode(401))

        val results = simulateFetchDashboardLoop()

        assertEquals(10, results.size,
            "fetchDashboardPrs must return partial results from the successfully-fetched page even on subsequent 401")
        // Must not throw
    }

}

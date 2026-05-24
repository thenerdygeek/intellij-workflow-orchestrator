package com.workflow.orchestrator.pullrequest.service

import com.workflow.orchestrator.core.bitbucket.BitbucketBranchClient
import com.workflow.orchestrator.core.bitbucket.BitbucketPrCommentResponse
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
 * Regression tests for audit finding pullrequest:B7 — listPrComments missing pagination loop.
 *
 * BitbucketServiceImpl.listPrComments() called api.listPrComments() only once, so PRs with
 * more than ~1000 activities (getPullRequestActivities 20-page cap) silently returned a
 * truncated comment list.
 *
 * The fix adds a 50-page loop at the service level. These tests operate at the
 * BitbucketBranchClient layer (same pattern as BitbucketServiceImplCommitsPaginationTest).
 *
 * Architecture note: BitbucketBranchClient.listPrComments() delegates to
 * getPullRequestActivities(), which has its own internal 20-page pagination loop.
 * Therefore:
 *  - listPrComments returns isLastPage=true whenever getPullRequestActivities completes
 *    without hitting its cap (regardless of how many internal pages were fetched).
 *  - listPrComments only returns isLastPage=false when getPullRequestActivities exhausts
 *    its 20-page cap — i.e. the PR has >1000 activities (extremely large PRs only).
 *
 * Tests 1–3 verify client-level single-page behavior via listPrComments.
 * Tests 4–5 verify the pagination seam (getPullRequestActivities cap propagation and the
 *   service-layer loop logic) at the getPullRequestActivities level — which is the actual
 *   source of isLastPage=false that BitbucketServiceImpl's loop depends on.
 */
class BitbucketServiceImplListPrCommentsPaginationTest {

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

    /** Single activities page with one COMMENTED entry and pagination fields. */
    private fun activitiesPage(
        commentId: Long,
        commentText: String,
        isLastPage: Boolean,
        nextPageStart: Int? = null,
    ): String {
        val nextField = if (nextPageStart != null) ""","nextPageStart":$nextPageStart""" else ""
        return """{
            "values": [
                {
                    "id": $commentId,
                    "createdDate": 1700000000000,
                    "user": {"name": "alice", "displayName": "Alice"},
                    "action": "COMMENTED",
                    "comment": {
                        "id": $commentId,
                        "version": 1,
                        "text": "$commentText",
                        "author": {"name": "alice", "displayName": "Alice"},
                        "createdDate": 1700000000000,
                        "updatedDate": 1700000000000,
                        "comments": []
                    }
                }
            ],
            "isLastPage": $isLastPage,
            "size": 1
            $nextField
        }""".trimIndent()
    }

    /** Empty activities page (non-COMMENTED action) — contributes no comments. */
    private fun emptyActivitiesPage(isLastPage: Boolean, nextPageStart: Int? = null): String {
        val nextField = if (nextPageStart != null) ""","nextPageStart":$nextPageStart""" else ""
        return """{
            "values": [
                {
                    "id": 999,
                    "createdDate": 1700000000000,
                    "user": {"name": "alice", "displayName": "Alice"},
                    "action": "APPROVED"
                }
            ],
            "isLastPage": $isLastPage,
            "size": 1
            $nextField
        }""".trimIndent()
    }

    @Test
    fun `listPrComments default start=0 appears in activities URL`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            activitiesPage(commentId = 1, commentText = "First comment", isLastPage = true)
        ))

        val result = client.listPrComments("P", "R", prId = 42)
        assertTrue(result is ApiResult.Success, "expected Success but got $result")

        val request = server.takeRequest()
        val path = request.path ?: ""
        assertTrue(path.contains("start=0"), "Expected start=0 in URL; path=$path")
    }

    @Test
    fun `listPrComments explicit start is threaded into activities URL`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            activitiesPage(commentId = 2, commentText = "Second page comment", isLastPage = true)
        ))

        val result = client.listPrComments("P", "R", prId = 42, start = 50)
        assertTrue(result is ApiResult.Success, "expected Success but got $result")

        val request = server.takeRequest()
        val path = request.path ?: ""
        assertTrue(path.contains("start=50"), "Expected start=50 in URL; path=$path")
    }

    @Test
    fun `listPrComments single page returns all comments and isLastPage=true`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            activitiesPage(commentId = 10, commentText = "Only comment", isLastPage = true)
        ))

        val result = client.listPrComments("P", "R", prId = 42)
        assertTrue(result is ApiResult.Success)
        val comments = (result as ApiResult.Success).data
        assertEquals(1, comments.values.size)
        assertEquals("Only comment", comments.values[0].text)
        // getPullRequestActivities completed normally → isLastPage=true in returned list
        assertTrue(comments.isLastPage,
            "listPrComments must return isLastPage=true when getPullRequestActivities completes normally")
    }

    /**
     * Verifies that getPullRequestActivities returns isLastPage=false when it exhausts its
     * 20-page cap — which is the condition BitbucketServiceImpl's service-layer loop uses
     * to decide whether to fetch another batch.
     *
     * We queue exactly 20 pages of isLastPage=false (simulating a PR with >1000 activities),
     * which forces getPullRequestActivities to hit its cap and return isLastPage=false.
     * A final page is NOT queued because the loop breaks at the cap before making call 21.
     * The returned BitbucketPrCommentList should also carry isLastPage=false + nextPageStart.
     */
    @Test
    fun `getPullRequestActivities cap hit produces isLastPage=false forwarded through listPrComments`() = runTest {
        val maxPages = 20  // getPullRequestActivities internal cap
        // Queue exactly 20 responses each with isLastPage=false → cap is hit after 20 fetches
        repeat(maxPages) { page ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(
                activitiesPage(
                    commentId = page.toLong() + 1,
                    commentText = "Comment $page",
                    isLastPage = false,
                    nextPageStart = (page + 1) * 50,
                )
            ))
        }

        val result = client.listPrComments("P", "R", prId = 42, start = 0)
        assertTrue(result is ApiResult.Success, "expected Success after 20-page cap; got $result")
        val page = (result as ApiResult.Success).data

        // Cap hit → getPullRequestActivities returns isLastPage=false, forwarded through listPrComments
        assertEquals(false, page.isLastPage,
            "isLastPage must be false when getPullRequestActivities hits its 20-page cap")
        assertTrue(page.nextPageStart != null,
            "nextPageStart must be set so the service-layer loop can continue")
        // All 20 comments from the 20 pages should be aggregated
        assertEquals(maxPages, page.values.size,
            "All $maxPages comments from capped pages must be returned")
    }

    /**
     * Simulates the BitbucketServiceImpl service-layer 50-page loop.
     *
     * Each call to client.listPrComments() represents one getPullRequestActivities batch.
     * In practice the service loop fires when the first batch returns isLastPage=false
     * (i.e. getPullRequestActivities hit its 20-page cap at the first cursor).
     *
     * Here we simulate with two sequential listPrComments calls — the first returns
     * isLastPage=false (20-page cap simulated by queueing 20 responses), and the second
     * returns isLastPage=true (1 response). The loop collects all comments from both batches.
     */
    @Test
    fun `service-layer loop collects comments across multiple getPullRequestActivities batches`() = runTest {
        val capPages = 20  // first batch: cap hit after 20 pages
        // First batch: 20 pages isLastPage=false → listPrComments returns isLastPage=false, nextPageStart=1000
        repeat(capPages) { page ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(
                activitiesPage(
                    commentId = page.toLong() + 1,
                    commentText = "Batch1 Comment $page",
                    isLastPage = false,
                    nextPageStart = (page + 1) * 50,
                )
            ))
        }
        // Second batch: 1 page isLastPage=true → listPrComments returns isLastPage=true
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            activitiesPage(commentId = 999, commentText = "Batch2 Comment", isLastPage = true)
        ))

        // Simulate the service-layer loop:
        //   1. Fetch batch 1 (start=0)   → isLastPage=false, nextPageStart=1000
        //   2. Fetch batch 2 (start=1000) → isLastPage=true  → break
        val allComments = mutableListOf<BitbucketPrCommentResponse>()
        var cursor = 0
        var pages = 0
        val maxServicePages = 50
        while (pages < maxServicePages) {
            val result = client.listPrComments("P", "R", prId = 42, start = cursor)
            assertTrue(result is ApiResult.Success, "batch $pages must succeed; got $result")
            val batch = (result as ApiResult.Success).data
            allComments += batch.values
            if (batch.isLastPage || batch.nextPageStart == null) break
            cursor = batch.nextPageStart!!
            pages++
        }

        // Batch 1 contributes 20 comments; batch 2 contributes 1 comment
        assertEquals(capPages + 1, allComments.size,
            "Service loop must aggregate comments from all batches")
        assertTrue(allComments.any { it.text == "Batch2 Comment" },
            "Last batch's comments must be included")

        // Verify that the second batch request was sent with nextPageStart from batch 1
        // (skip the 20 requests from batch 1, take the 21st which is batch 2's request)
        repeat(capPages) { server.takeRequest() }  // consume batch 1 requests
        val batch2Req = server.takeRequest()
        assertTrue(batch2Req.path?.contains("start=1000") == true,
            "Second batch must use nextPageStart=1000 from first batch; path=${batch2Req.path}")
    }
}

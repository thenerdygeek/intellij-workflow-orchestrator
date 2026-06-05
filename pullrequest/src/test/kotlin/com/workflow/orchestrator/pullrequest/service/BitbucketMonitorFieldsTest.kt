package com.workflow.orchestrator.pullrequest.service

import com.intellij.testFramework.LoggedErrorProcessorEnabler
import com.workflow.orchestrator.core.bitbucket.BitbucketBranchClient
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Mock-HTTP integration tests pinning the monitor-critical JSON→model parse paths for
 * [BitbucketBranchClient.getPullRequestDetail], [BitbucketBranchClient.getPullRequestParticipants],
 * and [BitbucketBranchClient.getBlockerComments].
 *
 * Architecture note: [BitbucketServiceImpl] depends on IntelliJ platform services
 * (PluginSettings, BitbucketBranchClientCache) and is not directly instantiable in a
 * headless test. Following the established pattern in PrActionServiceDeclineRetryTest
 * and BitbucketServiceImplCommitsPaginationTest, these tests operate at the
 * [BitbucketBranchClient] layer — the same client [BitbucketServiceImpl] delegates to.
 *
 * Monitor-relevant fields asserted:
 * - [getPullRequestDetail]: [BitbucketPrDetail.state] (OPEN/MERGED/DECLINED)
 * - [getPullRequestParticipants]: [BitbucketPrParticipantDetail.status] (APPROVED/NEEDS_WORK/UNAPPROVED),
 *   [BitbucketPrParticipantDetail.approved], [BitbucketPrParticipantDetail.role]
 * - [getBlockerComments] (countOnly=true): effectiveCount Int
 */
@ExtendWith(LoggedErrorProcessorEnabler.DoNoRethrowErrors::class)
class BitbucketMonitorFieldsTest {

    private lateinit var server: MockWebServer
    private lateinit var client: BitbucketBranchClient

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = BitbucketBranchClient(
            baseUrl = server.url("/").toString().trimEnd('/'),
            tokenProvider = { "test-token" }
        )
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    // ── getPullRequestDetail: state (OPEN / MERGED / DECLINED) ───────────────────

    @Test
    fun `getPullRequestDetail parses state OPEN from realistic Bitbucket DC PR JSON`() = runTest {
        // Realistic Bitbucket DC 9.4 GET /rest/api/1.0/projects/{p}/repos/{r}/pull-requests/{id} shape.
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{
                  "id": 42,
                  "title": "Feature: add login endpoint",
                  "description": "Adds POST /api/login with JWT response.",
                  "state": "OPEN",
                  "version": 3,
                  "author": {"user": {"name": "asmith", "displayName": "Alice Smith", "emailAddress": "alice@example.com", "slug": "asmith"}},
                  "reviewers": [
                    {"user": {"name": "bjones", "displayName": "Bob Jones", "slug": "bjones"}, "role": "REVIEWER", "approved": false, "status": "UNAPPROVED"}
                  ],
                  "fromRef": {"id": "refs/heads/feature/login", "displayId": "feature/login", "latestCommit": "abc123"},
                  "toRef":   {"id": "refs/heads/main",          "displayId": "main",          "latestCommit": "def456"},
                  "createdDate": 1717689600000,
                  "updatedDate": 1717693200000,
                  "links": {"self": [{"href": "https://bitbucket.example.com/projects/P/repos/R/pull-requests/42"}]}
                }"""
            )
        )

        val result = client.getPullRequestDetail("P", "R", prId = 42)

        assertTrue(result is ApiResult.Success, "Expected success; got: $result")
        val pr = (result as ApiResult.Success).data

        // Monitor-critical field: state
        assertEquals("OPEN", pr.state, "PullRequestDetailData.state must be 'OPEN'")
        assertEquals(42, pr.id)
        assertEquals(3, pr.version)
        assertEquals("Alice Smith", pr.author?.user?.displayName)
    }

    @Test
    fun `getPullRequestDetail parses state MERGED for a merged PR`() = runTest {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{
                  "id": 17,
                  "title": "Merge hotfix into main",
                  "state": "MERGED",
                  "version": 5,
                  "author": {"user": {"name": "cdev", "displayName": "Carol Dev", "slug": "cdev"}},
                  "reviewers": [],
                  "fromRef": {"id": "refs/heads/hotfix/PROJ-99", "displayId": "hotfix/PROJ-99", "latestCommit": "aaa111"},
                  "toRef":   {"id": "refs/heads/main",           "displayId": "main",           "latestCommit": "bbb222"},
                  "createdDate": 1717689600000,
                  "updatedDate": 1717700000000,
                  "links": {"self": [{"href": "https://bitbucket.example.com/projects/P/repos/R/pull-requests/17"}]}
                }"""
            )
        )

        val result = client.getPullRequestDetail("P", "R", prId = 17)

        assertTrue(result is ApiResult.Success)
        assertEquals("MERGED", (result as ApiResult.Success).data.state)
    }

    @Test
    fun `getPullRequestDetail parses state DECLINED`() = runTest {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{
                  "id": 5,
                  "title": "Experimental WIP branch",
                  "state": "DECLINED",
                  "version": 1,
                  "author": {"user": {"name": "intern", "displayName": "New Intern", "slug": "intern"}},
                  "reviewers": [],
                  "fromRef": {"id": "refs/heads/wip/experiment", "displayId": "wip/experiment", "latestCommit": "ccc333"},
                  "toRef":   {"id": "refs/heads/main",           "displayId": "main",           "latestCommit": "ddd444"},
                  "createdDate": 1717689600000,
                  "updatedDate": 1717690000000,
                  "links": {"self": [{"href": "https://bitbucket.example.com/projects/P/repos/R/pull-requests/5"}]}
                }"""
            )
        )

        val result = client.getPullRequestDetail("P", "R", prId = 5)

        assertTrue(result is ApiResult.Success)
        assertEquals("DECLINED", (result as ApiResult.Success).data.state)
    }

    @Test
    fun `getPullRequestDetail surfaces error on 404`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))

        val result = client.getPullRequestDetail("P", "R", prId = 9999)

        assertTrue(result is ApiResult.Error)
        assertEquals(ErrorType.NOT_FOUND, (result as ApiResult.Error).type)
    }

    @Test
    fun `getPullRequestDetail surfaces AUTH_FAILED on 401`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        val result = client.getPullRequestDetail("P", "R", prId = 42)

        assertTrue(result is ApiResult.Error)
        assertEquals(ErrorType.AUTH_FAILED, (result as ApiResult.Error).type)
    }

    // ── getPullRequestParticipants: status / approved / role ─────────────────────

    @Test
    fun `getPullRequestParticipants parses APPROVED status and approved=true for a reviewer who approved`() = runTest {
        // Realistic Bitbucket DC 9.4 GET /pull-requests/{id}/participants shape (R-SWAP-5).
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{
                  "values": [
                    {
                      "user":     {"name": "asmith", "displayName": "Alice Smith",  "slug": "asmith"},
                      "role":     "AUTHOR",
                      "approved": false,
                      "status":   "UNAPPROVED"
                    },
                    {
                      "user":     {"name": "bjones", "displayName": "Bob Jones",    "slug": "bjones"},
                      "role":     "REVIEWER",
                      "approved": true,
                      "status":   "APPROVED",
                      "lastReviewedCommit": "abc1234567890"
                    },
                    {
                      "user":     {"name": "cdev",   "displayName": "Carol Dev",    "slug": "cdev"},
                      "role":     "REVIEWER",
                      "approved": false,
                      "status":   "NEEDS_WORK",
                      "lastReviewedCommit": "abc1234567890"
                    }
                  ],
                  "size": 3,
                  "isLastPage": true
                }"""
            )
        )

        val result = client.getPullRequestParticipants("P", "R", prId = 42)

        assertTrue(result is ApiResult.Success, "Expected success; got: $result")
        val participants = (result as ApiResult.Success).data.values
        assertEquals(3, participants.size)

        // AUTHOR participant
        val author = participants[0]
        assertEquals("AUTHOR", author.role)
        assertEquals("UNAPPROVED", author.status)
        assertFalse(author.approved)

        // Monitor-critical fields: APPROVED reviewer
        val approved = participants[1]
        assertEquals("REVIEWER", approved.role)
        assertEquals("APPROVED", approved.status, "Participant status must be 'APPROVED'")
        assertTrue(approved.approved, "approved must be true for an APPROVED participant")
        assertEquals("abc1234567890", approved.lastReviewedCommit)

        // NEEDS_WORK reviewer
        val needsWork = participants[2]
        assertEquals("REVIEWER", needsWork.role)
        assertEquals("NEEDS_WORK", needsWork.status, "Participant status must be 'NEEDS_WORK'")
        assertFalse(needsWork.approved)
    }

    @Test
    fun `getPullRequestParticipants returns empty list when no participants`() = runTest {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"values":[],"size":0,"isLastPage":true}"""
            )
        )

        val result = client.getPullRequestParticipants("P", "R", prId = 42)

        assertTrue(result is ApiResult.Success)
        assertTrue((result as ApiResult.Success).data.values.isEmpty())
    }

    @Test
    fun `getPullRequestParticipants surfaces error on 404`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))

        val result = client.getPullRequestParticipants("P", "R", prId = 9999)

        assertTrue(result is ApiResult.Error)
        assertEquals(ErrorType.NOT_FOUND, (result as ApiResult.Error).type)
    }

    // ── getBlockerComments (countOnly=true): effectiveCount Int ──────────────────

    @Test
    fun `getBlockerComments countOnly=true parses count field from Bitbucket DC count-only response`() = runTest {
        // Bitbucket DC's ?count=true response returns only {"count": N} with no values array.
        // effectiveCount = count ?: values.size.takeIf { it > 0 } ?: size
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"count": 3}"""
            )
        )

        val result = client.getBlockerComments("P", "R", prId = 42, countOnly = true)

        assertTrue(result is ApiResult.Success, "Expected success; got: $result")
        val response = (result as ApiResult.Success).data
        assertEquals(3, response.effectiveCount, "effectiveCount must be 3 from count-only response")
    }

    @Test
    fun `getBlockerComments countOnly=false parses values array and derives effectiveCount`() = runTest {
        // Full listing response: {"size": 2, "values": [...]}  (no "count" field)
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{
                  "size": 2,
                  "values": [
                    {"id": 101, "text": "This is a blocker.", "severity": "BLOCKER",
                     "author": {"name": "reva", "displayName": "Reviewer A", "slug": "reva"},
                     "createdDate": 1717689600000},
                    {"id": 102, "text": "Another blocker issue found.", "severity": "BLOCKER",
                     "author": {"name": "revb", "displayName": "Reviewer B", "slug": "revb"},
                     "createdDate": 1717690000000}
                  ],
                  "isLastPage": true
                }"""
            )
        )

        val result = client.getBlockerComments("P", "R", prId = 42, countOnly = false)

        assertTrue(result is ApiResult.Success)
        val response = (result as ApiResult.Success).data
        assertEquals(2, response.effectiveCount, "effectiveCount must be 2 from values.size")
        assertEquals(2, response.values.size)
    }

    @Test
    fun `getBlockerComments returns zero effectiveCount when no blockers`() = runTest {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"count": 0}"""
            )
        )

        val result = client.getBlockerComments("P", "R", prId = 42, countOnly = true)

        assertTrue(result is ApiResult.Success)
        assertEquals(0, (result as ApiResult.Success).data.effectiveCount)
    }

    @Test
    fun `getBlockerComments surfaces error on 401`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        val result = client.getBlockerComments("P", "R", prId = 42, countOnly = true)

        assertTrue(result is ApiResult.Error)
        assertEquals(ErrorType.AUTH_FAILED, (result as ApiResult.Error).type)
    }

    @Test
    fun `getBlockerComments surfaces error on 404`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))

        val result = client.getBlockerComments("P", "R", prId = 9999, countOnly = true)

        assertTrue(result is ApiResult.Error)
        assertEquals(ErrorType.NOT_FOUND, (result as ApiResult.Error).type)
    }
}

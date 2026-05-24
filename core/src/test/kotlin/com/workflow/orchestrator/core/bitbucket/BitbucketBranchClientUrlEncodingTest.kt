package com.workflow.orchestrator.core.bitbucket

import com.workflow.orchestrator.core.model.ApiResult
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * C5 regression tests — filterText and branchName must be percent-encoded
 * via OkHttp HttpUrl.addQueryParameter, not concatenated raw into the URL.
 *
 * Before the fix, `filterText = "a&b=c#d"` was appended as-is, creating extra
 * query parameters and corrupting the URL. `branchName = "feature/foo bar"`
 * produced a URL OkHttp refused to build.
 *
 * After the fix, HttpUrl.addQueryParameter handles encoding on both values,
 * so the query parameter value that arrives at the server is the literal string
 * the caller passed — no injection, no crash.
 */
class BitbucketBranchClientUrlEncodingTest {

    private lateinit var server: MockWebServer
    private lateinit var client: BitbucketBranchClient

    private val emptyBranchList = """{"values":[],"size":0,"isLastPage":true}"""
    private val emptyPrList = """{"values":[],"size":0,"isLastPage":true}"""

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

    // ── getBranches — filterText encoding ────────────────────────────────

    @Test
    fun `getBranches encodes filterText containing ampersand and equals`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(emptyBranchList))

        val result = client.getBranches("PROJ", "my-repo", filterText = "a&b=c#d")

        assertTrue(result is ApiResult.Success)
        val req = server.takeRequest()
        val path = req.path!!

        // After percent-encoding, 'a&b=c#d' must arrive as the SINGLE value of
        // the filterText query parameter — not split into three separate tokens.
        assertTrue(
            path.contains("filterText=a%26b%3Dc%23d") || path.contains("filterText=a%26b%3Dc%23d"),
            "filterText with special chars must be encoded; got path: $path"
        )
        // The raw '&' must never appear after 'filterText=' as that would split params.
        val afterFilterText = path.substringAfter("filterText=", "")
        assertFalse(
            afterFilterText.startsWith("a&"),
            "Raw '&' after filterText= means injection succeeded (unencoded); got: $path"
        )
    }

    @Test
    fun `getBranches with blank filterText omits the parameter`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(emptyBranchList))

        client.getBranches("PROJ", "my-repo", filterText = "")

        val req = server.takeRequest()
        val path = req.path!!
        assertFalse(path.contains("filterText"), "Blank filterText must not add param; got: $path")
    }

    @Test
    fun `getBranches includes required fixed parameters`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(emptyBranchList))

        client.getBranches("PROJ", "my-repo")

        val req = server.takeRequest()
        val path = req.path!!
        assertTrue(path.contains("limit=100"), "Must include limit=100; got: $path")
        assertTrue(path.contains("orderBy=MODIFICATION"), "Must include orderBy; got: $path")
        assertTrue(path.contains("details=true"), "Must include details=true; got: $path")
    }

    // ── getPullRequestsForBranch — branchName encoding ───────────────────

    @Test
    fun `getPullRequestsForBranch encodes branchName with slash and space`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(emptyPrList))

        val result = client.getPullRequestsForBranch("PROJ", "my-repo", branchName = "feature/foo bar")

        assertTrue(result is ApiResult.Success)
        val req = server.takeRequest()
        val path = req.path!!

        // The 'at' param value must be percent-encoded — space → %20.
        // OkHttp encodes slashes inside query-param values as %2F.
        assertTrue(
            path.contains("at=") && path.contains("foo"),
            "Expected 'at' param with encoded branch name; got: $path"
        )
        // A raw space in the URL would make OkHttp reject the build() call —
        // the fact that we reach this assertion proves the URL was built.
        assertFalse(
            path.contains("at=refs/heads/feature/foo bar"),
            "Branch name with raw space must be encoded; got: $path"
        )
    }

    @Test
    fun `getPullRequestsForBranch literal value equals what caller passed`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(emptyPrList))

        client.getPullRequestsForBranch("PROJ", "my-repo", branchName = "main")

        val req = server.takeRequest()
        val path = req.path!!
        assertTrue(path.contains("at=refs%2Fheads%2Fmain") || path.contains("at=refs/heads/main"),
            "Expected 'at=refs/heads/main'; got: $path")
        assertTrue(path.contains("direction=OUTGOING"), "Expected direction param; got: $path")
        assertTrue(path.contains("state=OPEN"), "Expected state=OPEN param; got: $path")
    }
}

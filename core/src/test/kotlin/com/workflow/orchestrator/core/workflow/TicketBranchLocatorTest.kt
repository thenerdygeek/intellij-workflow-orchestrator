package com.workflow.orchestrator.core.workflow

import com.workflow.orchestrator.core.bitbucket.BitbucketBranch
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import com.workflow.orchestrator.core.settings.RepoConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.test.assertIs

class TicketBranchLocatorTest {

    private fun repo(name: String, root: String): RepoConfig = RepoConfig().apply {
        this.name = name
        this.bitbucketProjectKey = "PROJ"
        this.bitbucketRepoSlug = name.lowercase()
        this.localVcsRootPath = root
        this.defaultTargetBranch = "develop"
    }

    @Test
    fun `locate returns one row per repo when both have a matching branch`() = runTest {
        val repoA = repo("RepoA", "/work/repoA")
        val repoB = repo("RepoB", "/work/repoB")

        val branchClient = StubBranchClient(
            mapOf(
                ("PROJ" to "repoa") to listOf(branch("feature/ABC-123")),
                ("PROJ" to "repob") to listOf(branch("feature/ABC-123-api")),
            )
        )
        val locator = TicketBranchLocator.testInstance(
            repos = listOf(repoA, repoB),
            currentBranchOf = { "main" },
            targetBranchOf = { "develop" },
            isPathMounted = { true },
            branchClientFactory = { _ -> branchClient },
        )

        val result = assertIs<LocateResult.Configured>(locator.locate("ABC-123"))
        assertEquals(2, result.rows.size)
        assertEquals("feature/ABC-123", result.rows[0].branchDisplayId)
        assertEquals("feature/ABC-123-api", result.rows[1].branchDisplayId)
        assertEquals("develop", result.rows[0].targetBranchDisplayId)
        assertTrue(result.rows.none { it.isCheckedOut })
        assertTrue(result.rows.all { it.isPathMounted })
    }

    @Test
    fun `locate marks isCheckedOut true when local branch matches`() = runTest {
        val repoA = repo("RepoA", "/work/repoA")
        val branchClient = StubBranchClient(
            mapOf(("PROJ" to "repoa") to listOf(branch("feature/ABC-123")))
        )
        val locator = TicketBranchLocator.testInstance(
            repos = listOf(repoA),
            currentBranchOf = { if (it.localVcsRootPath == "/work/repoA") "feature/ABC-123" else null },
            targetBranchOf = { "develop" },
            isPathMounted = { true },
            branchClientFactory = { _ -> branchClient },
        )

        val rows = (locator.locate("ABC-123") as LocateResult.Configured).rows
        assertEquals(1, rows.size)
        assertTrue(rows[0].isCheckedOut)
    }

    @Test
    fun `locate skips repos that returned an API error`() = runTest {
        val repoA = repo("RepoA", "/work/repoA")
        val repoB = repo("RepoB", "/work/repoB")
        val branchClient = StubBranchClient(
            successes = mapOf(("PROJ" to "repoa") to listOf(branch("feature/ABC-123"))),
            errors = setOf("PROJ" to "repob"),
        )
        val locator = TicketBranchLocator.testInstance(
            repos = listOf(repoA, repoB),
            currentBranchOf = { null },
            targetBranchOf = { "develop" },
            isPathMounted = { true },
            branchClientFactory = { _ -> branchClient },
        )

        val rows = (locator.locate("ABC-123") as LocateResult.Configured).rows
        assertEquals(1, rows.size)
        assertEquals("RepoA", rows[0].repo.name)
    }

    @Test
    fun `second call within TTL is served from cache`() = runTest {
        val repoA = repo("RepoA", "/work/repoA")
        val branchClient = StubBranchClient(
            mapOf(("PROJ" to "repoa") to listOf(branch("feature/ABC-123")))
        )
        val locator = TicketBranchLocator.testInstance(
            repos = listOf(repoA),
            currentBranchOf = { null },
            targetBranchOf = { "develop" },
            isPathMounted = { true },
            branchClientFactory = { _ -> branchClient },
        )

        locator.locate("ABC-123")
        locator.locate("ABC-123")

        assertEquals(1, branchClient.callCount, "second call should hit cache, not Bitbucket")
    }

    @Test
    fun `invalidate clears the cache for that ticket`() = runTest {
        val repoA = repo("RepoA", "/work/repoA")
        val branchClient = StubBranchClient(
            mapOf(("PROJ" to "repoa") to listOf(branch("feature/ABC-123")))
        )
        val locator = TicketBranchLocator.testInstance(
            repos = listOf(repoA),
            currentBranchOf = { null },
            targetBranchOf = { "develop" },
            isPathMounted = { true },
            branchClientFactory = { _ -> branchClient },
        )

        locator.locate("ABC-123")
        locator.invalidate("ABC-123")
        locator.locate("ABC-123")

        assertEquals(2, branchClient.callCount)
    }

    @Test
    fun `repo with blank bitbucket coords is skipped silently`() = runTest {
        val unconfigured = RepoConfig().apply { name = "Unconfigured"; localVcsRootPath = "/x" }
        val branchClient = StubBranchClient(emptyMap())
        val locator = TicketBranchLocator.testInstance(
            repos = listOf(unconfigured),
            currentBranchOf = { null },
            targetBranchOf = { null },
            isPathMounted = { true },
            branchClientFactory = { _ -> branchClient },
        )

        // The only repo has no Bitbucket coords, so the locator returns NoReposConfigured.
        assertIs<LocateResult.NoReposConfigured>(locator.locate("ABC-123"))
        assertFalse(branchClient.calledFor("" to ""))
    }

    @Test
    fun `locate returns NoReposConfigured when settings has zero repos`() = runTest {
        val branchClient = StubBranchClient(emptyMap())
        val locator = TicketBranchLocator.testInstance(
            repos = emptyList(),
            currentBranchOf = { null },
            targetBranchOf = { null },
            isPathMounted = { true },
            branchClientFactory = { _ -> branchClient },
        )

        assertIs<LocateResult.NoReposConfigured>(locator.locate("ABC-123"))
    }

    @Test
    fun `anchored regex does not match longer ticket keys with shared prefix`() = runTest {
        // ABC-1 must NOT match feature/ABC-12 — common bug with naive .contains.
        val repoA = repo("RepoA", "/work/repoA")
        val branchClient = StubBranchClient(
            mapOf(("PROJ" to "repoa") to listOf(branch("feature/ABC-12")))
        )
        val locator = TicketBranchLocator.testInstance(
            repos = listOf(repoA),
            currentBranchOf = { null },
            targetBranchOf = { "develop" },
            isPathMounted = { true },
            branchClientFactory = { _ -> branchClient },
        )

        val result = locator.locate("ABC-1") as LocateResult.Configured
        assertTrue(result.rows.isEmpty(), "ABC-1 must not match feature/ABC-12")
    }

    @Test
    fun `multi-match per repo picks first and surfaces additionalMatchCount`() = runTest {
        val repoA = repo("RepoA", "/work/repoA")
        val branchClient = StubBranchClient(
            mapOf(("PROJ" to "repoa") to listOf(
                branch("feature/ABC-123"),       // most-recent — Bitbucket order
                branch("bugfix/ABC-123-hotfix"),
                branch("spike/ABC-123-prototype"),
            ))
        )
        val locator = TicketBranchLocator.testInstance(
            repos = listOf(repoA),
            currentBranchOf = { null },
            targetBranchOf = { "develop" },
            isPathMounted = { true },
            branchClientFactory = { _ -> branchClient },
        )

        val rows = (locator.locate("ABC-123") as LocateResult.Configured).rows
        assertEquals(1, rows.size)
        assertEquals("feature/ABC-123", rows[0].branchDisplayId)
        assertEquals(2, rows[0].additionalMatchCount, "two more matching branches in this repo")
    }

    @Test
    fun `unmounted repo path produces row with isPathMounted=false`() = runTest {
        // Configured repo whose localVcsRootPath no longer maps to a GitRepository
        // — Bitbucket lookup still works, but local Switch action would fail.
        val repoA = repo("RepoA", "/work/repoA")
        val branchClient = StubBranchClient(
            mapOf(("PROJ" to "repoa") to listOf(branch("feature/ABC-123")))
        )
        val locator = TicketBranchLocator.testInstance(
            repos = listOf(repoA),
            currentBranchOf = { null },
            targetBranchOf = { null },
            isPathMounted = { false },
            branchClientFactory = { _ -> branchClient },
        )

        val rows = (locator.locate("ABC-123") as LocateResult.Configured).rows
        assertEquals(1, rows.size)
        assertFalse(rows[0].isPathMounted)
        assertNull(rows[0].targetBranchDisplayId, "no target without a mounted local repo")
    }

    @Test
    fun `parallel fetch — total time bounded by slowest repo`() = runTest {
        // Two repos, each takes 200ms. Sequential = 400ms; parallel ~= 200ms.
        // Use runTest's virtual time: assert elapsed <= 350ms (some slack for scheduling).
        val repoA = repo("RepoA", "/work/repoA")
        val repoB = repo("RepoB", "/work/repoB")
        val slowClient = object : TicketBranchLocator.BranchSearchClient {
            override suspend fun search(projectKey: String, repoSlug: String, filterText: String): ApiResult<List<BitbucketBranch>> {
                delay(200)
                return ApiResult.Success(listOf(branch("feature/ABC-123")))
            }
        }
        val locator = TicketBranchLocator.testInstance(
            repos = listOf(repoA, repoB),
            currentBranchOf = { null },
            targetBranchOf = { "develop" },
            isPathMounted = { true },
            branchClientFactory = { _ -> slowClient },
        )

        val start = testScheduler.currentTime
        locator.locate("ABC-123")
        val elapsed = testScheduler.currentTime - start
        assertTrue(elapsed <= 350L, "expected parallel (~200ms), got ${elapsed}ms — looks sequential")
    }

    private fun branch(displayId: String): BitbucketBranch =
        BitbucketBranch(id = "refs/heads/$displayId", displayId = displayId)

    private class StubBranchClient(
        private val successes: Map<Pair<String, String>, List<BitbucketBranch>> = emptyMap(),
        private val errors: Set<Pair<String, String>> = emptySet(),
    ) : TicketBranchLocator.BranchSearchClient {
        var callCount: Int = 0; private set
        private val seen = mutableSetOf<Pair<String, String>>()

        override suspend fun search(projectKey: String, repoSlug: String, filterText: String): ApiResult<List<BitbucketBranch>> {
            callCount += 1
            seen += projectKey to repoSlug
            return when (projectKey to repoSlug) {
                in errors -> ApiResult.Error(ErrorType.SERVER_ERROR, "mock failure")
                else -> ApiResult.Success(successes[projectKey to repoSlug] ?: emptyList())
            }
        }

        fun calledFor(pair: Pair<String, String>): Boolean = pair in seen
    }
}

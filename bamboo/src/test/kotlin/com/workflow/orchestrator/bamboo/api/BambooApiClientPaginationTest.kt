package com.workflow.orchestrator.bamboo.api

import com.workflow.orchestrator.core.model.ApiResult
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Regression tests for audit finding bamboo:B8 — missing pagination loops in BambooApiClient.
 *
 * getPlans(), getLinkedRepositories(), getBranches(), and getPlanBranches() all used a
 * fixed max-results cap and returned at most one page of results, silently dropping items
 * when the server had more.
 *
 * The fix adds a shared 50-page paginate() helper that uses Bamboo's offset-based
 * pagination (size < maxResult → last page; next start-index = startIndex + size).
 */
class BambooApiClientPaginationTest {

    private lateinit var server: MockWebServer
    private lateinit var client: BambooApiClient

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = BambooApiClient(
            baseUrl = server.url("/").toString().trimEnd('/'),
            tokenProvider = { "test-token" }
        )
    }

    @AfterEach
    fun tearDown() { server.shutdown() }

    // ── helpers ──────────────────────────────────────────────────────────────────

    /** Plans page JSON in Bamboo's `expand=plans.plan` shape. */
    private fun plansPage(keys: List<String>, startIndex: Int, maxResult: Int): String {
        val planItems = keys.joinToString(",") { key ->
            """{"key":"$key","name":"Plan $key","shortName":"$key","shortKey":"${key.substringAfterLast('-')}","type":"chain","projectKey":"PROJ"}"""
        }
        return """{"plans":{"size":${keys.size},"max-result":$maxResult,"start-index":$startIndex,"plan":[$planItems]}}"""
    }

    /** Branches page JSON for /plan/{key}/branch. */
    private fun branchesPage(names: List<String>, startIndex: Int, maxResult: Int): String {
        val items = names.joinToString(",") { name ->
            """{"key":"PROJ-PLAN-$name","name":"$name","shortName":"$name","enabled":true}"""
        }
        return """{"branches":{"size":${names.size},"max-result":$maxResult,"start-index":$startIndex,"branch":[$items]}}"""
    }

    /** PlanBranches page JSON for /plan/{masterKey}/branch. */
    private fun planBranchesPage(keys: List<String>, startIndex: Int, maxResult: Int): String {
        val items = keys.joinToString(",") { key ->
            """{"key":"$key","name":"branch $key","shortName":"br","enabled":true}"""
        }
        return """{"branches":{"size":${keys.size},"max-result":$maxResult,"start-index":$startIndex,"branch":[$items]}}"""
    }

    /** LinkedRepositories page JSON for /repository. */
    private fun reposPage(ids: List<Int>, startIndex: Int, maxResult: Int): String {
        val items = ids.joinToString(",") { id ->
            """{"id":$id,"searchEntity":{"id":$id,"name":"repo-$id","repositoryUrl":"https://git.example.com/repo-$id"}}"""
        }
        return """{"searchResults":[$items],"size":${ids.size},"max-result":$maxResult,"start-index":$startIndex}"""
    }

    // ── getPlans ─────────────────────────────────────────────────────────────────

    @Test
    fun `getPlans default start-index=0 appears in URL`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            plansPage(listOf("PROJ-PLAN1"), startIndex = 0, maxResult = 100)
        ))

        val result = client.getPlans()
        assertTrue(result is ApiResult.Success)

        val request = server.takeRequest()
        assertTrue(request.path?.contains("start-index=0") == true,
            "Expected start-index=0 in URL; path=${request.path}")
    }

    @Test
    fun `getPlans single page returns all plans`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            plansPage(listOf("PROJ-PLAN1", "PROJ-PLAN2"), startIndex = 0, maxResult = 100)
        ))

        val result = client.getPlans()
        assertTrue(result is ApiResult.Success)
        val plans = (result as ApiResult.Success).data
        assertEquals(2, plans.size)
        assertEquals("PROJ-PLAN1", plans[0].key)
        assertEquals("PROJ-PLAN2", plans[1].key)
    }

    @Test
    fun `getPlans paginates across two pages collecting all plans`() = runTest {
        // Page 1: 100 plans (full page) → triggers fetch of page 2
        val page1Keys = (1..100).map { "PROJ-P$it" }
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            plansPage(page1Keys, startIndex = 0, maxResult = 100)
        ))
        // Page 2: 25 plans (partial page) → last page
        val page2Keys = (101..125).map { "PROJ-P$it" }
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            plansPage(page2Keys, startIndex = 100, maxResult = 100)
        ))

        val result = client.getPlans()
        assertTrue(result is ApiResult.Success)
        val plans = (result as ApiResult.Success).data
        assertEquals(125, plans.size, "All 125 plans from both pages must be collected")
        assertEquals("PROJ-P1", plans.first().key)
        assertEquals("PROJ-P125", plans.last().key)

        // Verify second request used start-index=100
        server.takeRequest() // page 1
        val req2 = server.takeRequest()
        assertTrue(req2.path?.contains("start-index=100") == true,
            "Second page must use start-index=100; path=${req2.path}")
    }

    @Test
    fun `getPlans returns error when first page fails`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        val result = client.getPlans()
        assertTrue(result is ApiResult.Error)
    }

    // ── getBranches ───────────────────────────────────────────────────────────────

    @Test
    fun `getBranches single page returns all branches`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            branchesPage(listOf("main", "develop"), startIndex = 0, maxResult = 100)
        ))

        val result = client.getBranches("PROJ-PLAN")
        assertTrue(result is ApiResult.Success)
        val branches = (result as ApiResult.Success).data
        assertEquals(2, branches.size)
        assertEquals("main", branches[0].name)
    }

    @Test
    fun `getBranches paginates across two pages collecting all branches`() = runTest {
        val page1Names = (1..100).map { "feature-$it" }
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            branchesPage(page1Names, startIndex = 0, maxResult = 100)
        ))
        val page2Names = listOf("hotfix-1", "hotfix-2")
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            branchesPage(page2Names, startIndex = 100, maxResult = 100)
        ))

        val result = client.getBranches("PROJ-PLAN")
        assertTrue(result is ApiResult.Success)
        val branches = (result as ApiResult.Success).data
        assertEquals(102, branches.size)

        server.takeRequest() // page 1
        val req2 = server.takeRequest()
        assertTrue(req2.path?.contains("start-index=100") == true,
            "Second page must use start-index=100; path=${req2.path}")
    }

    @Test
    fun `getBranches does not duplicate when server echoes start-index=0 on every page`() = runTest {
        // Real-Bamboo quirk: the /branch endpoint honours the requested start-index but
        // echoes `start-index: 0` in the body on every page. The old loop advanced the
        // offset from the echoed field (page.startIndex + page.size), so it stalled at
        // 100 and re-fetched the same window up to the 50-page cap → the same branch
        // repeated. The loop must advance from the *requested* offset + actual item count.
        val page0 = (1..100).map { "feature-$it" }   // full page  -> more to come
        val page100 = (101..200).map { "feature-$it" } // full page  -> more to come
        val page200 = listOf("hotfix-1", "hotfix-2")    // partial    -> last page
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val start = Regex("start-index=(\\d+)").find(request.path ?: "")?.groupValues?.get(1)?.toInt() ?: 0
                val names = when {
                    start <= 0 -> page0
                    start < 200 -> page100
                    else -> page200
                }
                // Always echo start-index=0 (the quirk).
                return MockResponse().setResponseCode(200)
                    .setBody(branchesPage(names, startIndex = 0, maxResult = 100))
            }
        }

        val result = client.getBranches("PROJ-PLAN")
        assertTrue(result is ApiResult.Success)
        val branches = (result as ApiResult.Success).data
        val keys = branches.map { it.key }
        assertEquals(keys.distinct().size, keys.size, "No branch key should appear more than once")
        assertEquals(202, branches.size, "All three pages collected exactly once")
    }

    // ── getPlanBranches ──────────────────────────────────────────────────────────

    @Test
    fun `getPlanBranches single page returns all plan branches`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            planBranchesPage(listOf("PROJ-PLAN-1", "PROJ-PLAN-2"), startIndex = 0, maxResult = 200)
        ))

        val result = client.getPlanBranches("PROJ-PLAN")
        assertTrue(result is ApiResult.Success)
        val branches = (result as ApiResult.Success).data
        assertEquals(2, branches.size)
    }

    @Test
    fun `getPlanBranches paginates across two pages collecting all plan branches`() = runTest {
        val page1Keys = (1..200).map { "PROJ-PLAN-$it" }
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            planBranchesPage(page1Keys, startIndex = 0, maxResult = 200)
        ))
        val page2Keys = listOf("PROJ-PLAN-201", "PROJ-PLAN-202")
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            planBranchesPage(page2Keys, startIndex = 200, maxResult = 200)
        ))

        val result = client.getPlanBranches("PROJ-PLAN")
        assertTrue(result is ApiResult.Success)
        val branches = (result as ApiResult.Success).data
        assertEquals(202, branches.size)

        server.takeRequest() // page 1
        val req2 = server.takeRequest()
        assertTrue(req2.path?.contains("start-index=200") == true,
            "Second page must use start-index=200; path=${req2.path}")
    }

    // ── getLinkedRepositories ────────────────────────────────────────────────────

    @Test
    fun `getLinkedRepositories single page returns all repositories`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            reposPage(listOf(1, 2, 3), startIndex = 0, maxResult = 200)
        ))

        val result = client.getLinkedRepositories()
        assertTrue(result is ApiResult.Success)
        val repos = (result as ApiResult.Success).data
        assertEquals(3, repos.size)
        assertEquals(1, repos[0].id)
        assertEquals("repo-1", repos[0].name)
    }

    @Test
    fun `getLinkedRepositories paginates across two pages collecting all repositories`() = runTest {
        val page1Ids = (1..200).toList()
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            reposPage(page1Ids, startIndex = 0, maxResult = 200)
        ))
        val page2Ids = listOf(201, 202)
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            reposPage(page2Ids, startIndex = 200, maxResult = 200)
        ))

        val result = client.getLinkedRepositories()
        assertTrue(result is ApiResult.Success)
        val repos = (result as ApiResult.Success).data
        assertEquals(202, repos.size)

        server.takeRequest() // page 1
        val req2 = server.takeRequest()
        assertTrue(req2.path?.contains("start-index=200") == true,
            "Second page must use start-index=200; path=${req2.path}")
    }

    @Test
    fun `getLinkedRepositories copies id from item wrapper into searchEntity`() = runTest {
        // The BambooLinkedRepositoryItem wraps a BambooLinkedRepository; the outer item.id
        // must be propagated into searchEntity via copy(id = item.id).
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            reposPage(listOf(42), startIndex = 0, maxResult = 200)
        ))

        val result = client.getLinkedRepositories()
        assertTrue(result is ApiResult.Success)
        val repos = (result as ApiResult.Success).data
        assertEquals(42, repos[0].id, "id must be copied from the BambooLinkedRepositoryItem wrapper")
    }
}

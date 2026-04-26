package com.workflow.orchestrator.bamboo.api

import com.workflow.orchestrator.core.model.ApiResult
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import com.intellij.testFramework.LoggedErrorProcessorEnabler
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(LoggedErrorProcessorEnabler.DoNoRethrowErrors::class)
class BambooApiClientPlanBranchTest {

    private lateinit var server: MockWebServer
    private lateinit var client: BambooApiClient

    private fun fixture(name: String): String =
        javaClass.classLoader.getResource("fixtures/$name")!!.readText()

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
    fun tearDown() {
        server.shutdown()
    }

    // ── getResultsByChangeset ─────────────────────────────────────────────────

    @Test
    fun `getResultsByChangeset parses results-result array and returns entries`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("results-by-changeset.json")))

        val result = client.getResultsByChangeset("abc123def456")

        assertTrue(result.isSuccess)
        val entries = (result as ApiResult.Success).data
        assertEquals(2, entries.size)
        assertEquals("PROJ-BUILD", entries[0].plan?.key)
        assertEquals("PROJ-BUILD-42", entries[0].planResultKey?.key)
        assertEquals("PROJ-DEPLOY", entries[1].plan?.key)

        val recorded = server.takeRequest()
        assertTrue(recorded.path!!.contains("/rest/api/latest/result/byChangeset/abc123def456"))
        assertEquals("Bearer test-token", recorded.getHeader("Authorization"))
    }

    @Test
    fun `getResultsByChangeset returns empty list on empty results`() = runTest {
        server.enqueue(MockResponse().setBody("""{"results":{"size":0,"result":[]}}"""))

        val result = client.getResultsByChangeset("deadbeef")

        assertTrue(result.isSuccess)
        assertTrue((result as ApiResult.Success).data.isEmpty())
    }

    @Test
    fun `getResultsByChangeset returns first plan key accessible`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("results-by-changeset.json")))

        val result = client.getResultsByChangeset("abc123")

        assertTrue(result.isSuccess)
        val entries = (result as ApiResult.Success).data
        val firstPlanKey = entries.firstOrNull()?.plan?.key
        assertEquals("PROJ-BUILD", firstPlanKey)
    }

    // ── getLinkedRepositories ─────────────────────────────────────────────────

    @Test
    fun `getLinkedRepositories parses searchResults array`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("linked-repositories.json")))

        val result = client.getLinkedRepositories()

        assertTrue(result.isSuccess)
        val repos = (result as ApiResult.Success).data
        assertEquals(2, repos.size)
        assertEquals("my-repo", repos[0].name)
        assertEquals("https://bitbucket.org/mycompany/myrepo.git", repos[0].repositoryUrl)
        assertEquals(10, repos[0].id)

        val recorded = server.takeRequest()
        assertTrue(recorded.path!!.contains("/rest/api/latest/repository"))
        assertEquals("Bearer test-token", recorded.getHeader("Authorization"))
    }

    @Test
    fun `getLinkedRepositories returns empty list on empty response`() = runTest {
        server.enqueue(MockResponse().setBody("""{"searchResults":[],"size":0,"max-result":0,"start-index":0}"""))

        val result = client.getLinkedRepositories()

        assertTrue(result.isSuccess)
        assertTrue((result as ApiResult.Success).data.isEmpty())
    }

    // ── getRepositoryUsedBy ───────────────────────────────────────────────────

    @Test
    fun `getRepositoryUsedBy parses results array including entityType`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("repository-used-by.json")))

        val result = client.getRepositoryUsedBy(10)

        assertTrue(result.isSuccess)
        val usages = (result as ApiResult.Success).data
        assertEquals(2, usages.size)
        assertEquals("PROJ-BUILD", usages[0].key)
        assertEquals("CHAIN", usages[0].entityType)
        assertEquals("DEPLOY-PROD", usages[1].key)
        assertEquals("DEPLOYMENT_PROJECT", usages[1].entityType)

        val recorded = server.takeRequest()
        assertTrue(recorded.path!!.contains("/rest/api/latest/repository/10/usedBy"))
    }

    @Test
    fun `getRepositoryUsedBy CHAIN entries are identifiable as plan usages`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("repository-used-by.json")))

        val result = client.getRepositoryUsedBy(10)

        assertTrue(result.isSuccess)
        val planUsages = (result as ApiResult.Success).data.filter { it.entityType == "CHAIN" }
        assertEquals(1, planUsages.size)
        assertEquals("PROJ-BUILD", planUsages[0].key)
    }

    // ── getPlanBranches ───────────────────────────────────────────────────────

    @Test
    fun `getPlanBranches parses branches-branch array`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("plan-branches.json")))

        val result = client.getPlanBranches("PROJ-BUILD")

        assertTrue(result.isSuccess)
        val branches = (result as ApiResult.Success).data
        assertEquals(3, branches.size)
        assertEquals("PROJ-BUILD-7", branches[0].key)
        assertEquals("feature/PROJ-123-add-login", branches[0].shortName)
        assertTrue(branches[0].enabled)
        assertFalse(branches[2].enabled)

        val recorded = server.takeRequest()
        assertTrue(recorded.path!!.contains("/rest/api/latest/plan/PROJ-BUILD/branch"))
        assertTrue(recorded.path!!.contains("max-results=200"))
    }

    @Test
    fun `getPlanBranches respects custom maxResults`() = runTest {
        server.enqueue(MockResponse().setBody("""{"branches":{"branch":[],"size":0,"max-result":50,"start-index":0}}"""))

        val result = client.getPlanBranches("PROJ-BUILD", maxResults = 50)

        assertTrue(result.isSuccess)
        val recorded = server.takeRequest()
        assertTrue(recorded.path!!.contains("max-results=50"))
    }

    @Test
    fun `getPlanBranches shortName lookup works for branch resolution`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("plan-branches.json")))

        val result = client.getPlanBranches("PROJ-BUILD")

        assertTrue(result.isSuccess)
        val branches = (result as ApiResult.Success).data
        val match = branches.firstOrNull { it.shortName == "feature/PROJ-123-add-login" }
        assertNotNull(match)
        assertEquals("PROJ-BUILD-7", match!!.key)
    }
}

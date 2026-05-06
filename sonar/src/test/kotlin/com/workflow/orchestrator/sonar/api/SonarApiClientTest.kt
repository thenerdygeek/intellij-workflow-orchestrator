package com.workflow.orchestrator.sonar.api

import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
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
class SonarApiClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: SonarApiClient

    private fun fixture(name: String): String =
        javaClass.classLoader.getResource("fixtures/$name")!!.readText()

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = SonarApiClient(
            baseUrl = server.url("/").toString().trimEnd('/'),
            tokenProvider = { "test-token" }
        )
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `validateConnection returns true for valid token`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("auth-validate.json")))

        val result = client.validateConnection()

        assertTrue(result.isSuccess)
        assertTrue((result as ApiResult.Success).data)
        val req = server.takeRequest()
        assertEquals("/api/authentication/validate", req.path)
        assertEquals("Bearer test-token", req.getHeader("Authorization"))
    }

    @Test
    fun `searchProjects returns matching projects`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("projects-search.json")))

        val result = client.searchProjects("my-app")

        assertTrue(result.isSuccess)
        val projects = (result as ApiResult.Success).data
        assertEquals(2, projects.size)
        assertEquals("com.myapp:my-app", projects[0].key)
    }

    @Test
    fun `getQualityGateStatus returns gate with conditions`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("qualitygate-status-passed.json")))

        val result = client.getQualityGateStatus("com.myapp:my-app")

        assertTrue(result.isSuccess)
        val gate = (result as ApiResult.Success).data
        assertEquals("OK", gate.status)
        assertEquals(3, gate.conditions.size)
    }

    @Test
    fun `getQualityGateStatus includes branch parameter`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("qualitygate-status-passed.json")))

        client.getQualityGateStatus("com.myapp:my-app", branch = "feature/test")

        val req = server.takeRequest()
        assertTrue(req.path!!.contains("branch=feature"))
    }

    @Test
    fun `getIssues returns issues with text ranges`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("issues-search.json")))

        val result = client.getIssues("com.myapp:my-app")

        assertTrue(result.isSuccess)
        val issues = (result as ApiResult.Success).data
        assertEquals(3, issues.size)
        assertEquals("BUG", issues[0].type)
        assertEquals(42, issues[0].textRange?.startLine)
        assertNull(issues[2].textRange)
    }

    @Test
    fun `getMeasures returns per-file coverage`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("measures-component-tree.json")))

        val result = client.getMeasures("com.myapp:my-app")

        assertTrue(result.isSuccess)
        val components = (result as ApiResult.Success).data
        assertEquals(2, components.size)
        val coverage = components[0].measures.first { it.metric == "coverage" }
        assertEquals("72.1", coverage.value)
    }

    @Test
    fun `getMeasures sends custom metricKeys in request URL`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("measures-component-tree.json")))

        client.getMeasures(
            "com.myapp:my-app",
            metricKeys = "coverage,line_coverage,new_coverage,new_branch_coverage"
        )

        val req = server.takeRequest()
        assertTrue(req.path!!.contains("new_coverage"))
        assertTrue(req.path!!.contains("new_branch_coverage"))
    }

    @Test
    fun `getMeasures sorts by new_lines_to_cover desc when that metric is in the request`() = runTest {
        // Default Sonar sort is alphabetical by name, which on large projects
        // can bury files with new code past the page cutoff (verified against a
        // 531-file project where the Coverage tab showed empty new-code listing
        // because the 31 truncated files included the only ones with new code).
        // Sorting by new_lines_to_cover desc puts files with the most new code
        // in page 1, regardless of their alphabetical position.
        server.enqueue(MockResponse().setBody(fixture("measures-component-tree.json")))

        client.getMeasures("com.myapp:my-app")

        val path = server.takeRequest().path!!
        assertTrue(path.contains("s=metric"), "missing s=metric in $path")
        assertTrue(path.contains("metricSort=new_lines_to_cover"), "missing metricSort in $path")
        assertTrue(path.contains("asc=false"), "missing asc=false in $path")
    }

    @Test
    fun `getMeasures does not request metricSort when new_lines_to_cover is absent from keys`() = runTest {
        // Sonar rejects metricSort=X with 400 if X isn't in metricKeys. Guard:
        // the sort param is conditional on new_lines_to_cover being requested.
        server.enqueue(MockResponse().setBody(fixture("measures-component-tree.json")))

        client.getMeasures("com.myapp:my-app", metricKeys = "coverage,line_coverage")

        val path = server.takeRequest().path!!
        assertFalse(path.contains("metricSort="), "must NOT include metricSort when new_lines_to_cover absent: $path")
    }

    @Test
    fun `getMeasures paginates until paging total is reached and merges components`() = runTest {
        // Without pagination, projects with > 500 files lose their tail to
        // truncation, which causes the new-code Coverage tab to silently miss
        // files when they sort past the cutoff. Pagination loops on `p=N` until
        // we have all `paging.total` components or the page returns empty.
        val page1 = """
            {
              "paging": {"pageIndex": 1, "pageSize": 500, "total": 600},
              "components": [
                {"key": "k1", "name": "A.kt", "qualifier": "FIL", "path": "A.kt",
                 "measures": [{"metric": "coverage", "value": "80"}]}
              ]
            }
        """.trimIndent()
        val page2 = """
            {
              "paging": {"pageIndex": 2, "pageSize": 500, "total": 600},
              "components": [
                {"key": "k2", "name": "B.kt", "qualifier": "FIL", "path": "B.kt",
                 "measures": [{"metric": "coverage", "value": "70"}]}
              ]
            }
        """.trimIndent()
        server.enqueue(MockResponse().setBody(page1))
        server.enqueue(MockResponse().setBody(page2))

        val result = client.getMeasures("com.myapp:my-app")

        assertTrue(result.isSuccess)
        val components = (result as ApiResult.Success).data
        assertEquals(2, components.size)
        assertEquals("A.kt", components[0].path)
        assertEquals("B.kt", components[1].path)

        val req1 = server.takeRequest()
        val req2 = server.takeRequest()
        assertTrue(req1.path!!.contains("p=1"), "first request should target page 1: ${req1.path}")
        assertTrue(req2.path!!.contains("p=2"), "second request should target page 2: ${req2.path}")
    }

    @Test
    fun `getMeasures stops paginating when a page returns empty components`() = runTest {
        // Defensive: if Sonar's paging.total is stale (e.g. components removed
        // mid-pagination), an empty components list means we're done — don't
        // loop forever waiting for total to be reached.
        val firstPage = """
            {
              "paging": {"pageIndex": 1, "pageSize": 500, "total": 1000},
              "components": [
                {"key": "k1", "name": "A.kt", "qualifier": "FIL", "path": "A.kt", "measures": []}
              ]
            }
        """.trimIndent()
        val emptyPage = """{"paging": {"pageIndex": 2, "pageSize": 500, "total": 1000}, "components": []}"""
        server.enqueue(MockResponse().setBody(firstPage))
        server.enqueue(MockResponse().setBody(emptyPage))

        val result = client.getMeasures("com.myapp:my-app")

        assertTrue(result.isSuccess)
        assertEquals(1, (result as ApiResult.Success).data.size)
        assertEquals(2, server.requestCount, "should have stopped after the empty page")
    }

    @Test
    fun `getMeasures default URL includes every metric the Quality tab renders`() = runTest {
        // Regression test: SonarDataService now calls getMeasures without an explicit
        // metricKeys arg, so the API client's DEFAULT_METRIC_KEYS drives the request.
        // The Coverage table and the new-code filter both depend on these keys being
        // present; if any go missing the user sees blank columns or an empty new-code
        // subset (silently masked by the old fallback). Pin the contract here.
        server.enqueue(MockResponse().setBody(fixture("measures-component-tree.json")))

        client.getMeasures("com.myapp:my-app")

        val path = server.takeRequest().path!!
        // New-code filter — without new_lines_to_cover, no files qualify as "new code"
        assertTrue(path.contains("new_lines_to_cover"), "missing new_lines_to_cover in $path")
        // New-code Coverage table columns
        assertTrue(path.contains("new_coverage"), "missing new_coverage in $path")
        assertTrue(path.contains("new_branch_coverage"), "missing new_branch_coverage in $path")
        assertTrue(path.contains("new_uncovered_lines"), "missing new_uncovered_lines in $path")
        // Overall Coverage table columns
        assertTrue(path.contains("line_coverage"), "missing line_coverage in $path")
        assertTrue(path.contains("branch_coverage"), "missing branch_coverage in $path")
        assertTrue(path.contains("uncovered_lines"), "missing uncovered_lines in $path")
        assertTrue(path.contains("uncovered_conditions"), "missing uncovered_conditions in $path")
        assertTrue(path.contains("complexity"), "missing complexity in $path")
        assertTrue(path.contains("cognitive_complexity"), "missing cognitive_complexity in $path")
        // additionalFields=period required for new_* metrics
        assertTrue(path.contains("additionalFields=period"), "missing additionalFields=period in $path")
    }

    @Test
    fun `getSourceLines returns per-line coverage data`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("source-lines.json")))

        val result = client.getSourceLines("com.myapp:my-app:src/main/kotlin/com/myapp/service/UserService.kt")

        assertTrue(result.isSuccess)
        val lines = (result as ApiResult.Success).data
        assertEquals(8, lines.size)
        assertEquals(5, lines[0].lineHits)
        assertEquals(0, lines[6].lineHits)
    }

    @Test
    fun `returns AUTH_FAILED on 401`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        val result = client.validateConnection()

        assertTrue(result.isError)
        assertEquals(ErrorType.AUTH_FAILED, (result as ApiResult.Error).type)
    }

    @Test
    fun `returns NOT_FOUND on 404`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))

        val result = client.getQualityGateStatus("nonexistent")

        assertTrue(result.isError)
        assertEquals(ErrorType.NOT_FOUND, (result as ApiResult.Error).type)
    }
}

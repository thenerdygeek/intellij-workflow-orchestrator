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
    fun `componentExists returns true when SonarQube returns 200`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"component":{"key":"acme-common-core","name":"acme-common-core","qualifier":"TRK"}}"""
            )
        )

        val result = client.componentExists("acme-common-core")

        assertTrue(result.isSuccess)
        assertTrue((result as ApiResult.Success).data)
        val req = server.takeRequest()
        assertEquals("/api/components/show?component=acme-common-core", req.path)
    }

    @Test
    fun `componentExists returns false when SonarQube returns 404`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"errors":[{"msg":"not found"}]}"""))

        val result = client.componentExists("com.acme.platform:acme-common-core")

        assertTrue(result.isSuccess)
        assertFalse((result as ApiResult.Success).data)
    }

    @Test
    fun `componentExists propagates non-404 errors so callers can fall through`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        val result = client.componentExists("acme-common-core")

        assertFalse(result.isSuccess)
        assertEquals(ErrorType.AUTH_FAILED, (result as ApiResult.Error).type)
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
    fun `getIssues with filePath scopes componentKeys to the file component`() = runTest {
        // Sonar's /api/issues/search filters by component via componentKeys=projectKey:path.
        // Sending a bare &components=path (the prior broken behavior) is silently ignored
        // by Sonar, which then returns the unfiltered 500-issue project-wide result —
        // exactly the symptom users reported on 2026-05-18.
        server.enqueue(MockResponse().setBody(fixture("issues-search.json")))

        client.getIssues(
            projectKey = "com.myapp:my-app",
            filePath = "src/main/java/com/example/AbstractService.java"
        )

        val path = server.takeRequest().path!!
        val decoded = java.net.URLDecoder.decode(path, "UTF-8")
        assertTrue(
            decoded.contains("componentKeys=com.myapp:my-app:src/main/java/com/example/AbstractService.java"),
            "expected componentKeys=<projectKey>:<filePath> in $decoded",
        )
        assertFalse(
            decoded.contains("&components="),
            "must NOT send the ignored &components= param: $decoded",
        )
    }

    @Test
    fun `getIssuesWithPaging with filePath scopes componentKeys to the file component`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("issues-search.json")))

        client.getIssuesWithPaging(
            projectKey = "com.myapp:my-app",
            filePath = "src/main/java/com/example/AbstractService.java"
        )

        val path = server.takeRequest().path!!
        val decoded = java.net.URLDecoder.decode(path, "UTF-8")
        assertTrue(
            decoded.contains("componentKeys=com.myapp:my-app:src/main/java/com/example/AbstractService.java"),
            "expected componentKeys=<projectKey>:<filePath> in $decoded",
        )
        assertFalse(
            decoded.contains("&components="),
            "must NOT send the ignored &components= param: $decoded",
        )
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

    // ── F-7: Issues pagination ─────────────────────────────────────────────

    @Test
    fun `getIssuesWithPaging fetches all 3 pages and combines issues`() = runTest {
        // 3 pages × 2 items each; total=6 declared on every page.
        fun issuePage(pageIndex: Int, key1: String, key2: String) = """
            {
              "paging": {"pageIndex": $pageIndex, "pageSize": 2, "total": 6},
              "issues": [
                {"key": "$key1", "rule": "r1", "severity": "MAJOR", "message": "m",
                 "component": "c", "type": "BUG", "status": "OPEN"},
                {"key": "$key2", "rule": "r1", "severity": "MAJOR", "message": "m",
                 "component": "c", "type": "BUG", "status": "OPEN"}
              ]
            }
        """.trimIndent()
        server.enqueue(MockResponse().setBody(issuePage(1, "i1", "i2")))
        server.enqueue(MockResponse().setBody(issuePage(2, "i3", "i4")))
        server.enqueue(MockResponse().setBody(issuePage(3, "i5", "i6")))

        val result = client.getIssuesWithPaging("proj")

        assertTrue(result.isSuccess)
        val data = (result as ApiResult.Success).data
        assertEquals(6, data.issues.size)
        assertEquals(listOf("i1","i2","i3","i4","i5","i6"), data.issues.map { it.key })
        assertEquals(3, server.requestCount)
        // Verify p= parameter increments correctly
        val paths = (1..3).map { server.takeRequest().path!! }
        assertTrue(paths[0].contains("p=1"), "page 1 path: ${paths[0]}")
        assertTrue(paths[1].contains("p=2"), "page 2 path: ${paths[1]}")
        assertTrue(paths[2].contains("p=3"), "page 3 path: ${paths[2]}")
    }

    @Test
    fun `getIssuesWithPaging stops when all items accumulated before max pages`() = runTest {
        val single = """
            {"paging": {"pageIndex": 1, "pageSize": 500, "total": 1},
             "issues": [{"key": "only", "rule": "r", "severity": "MINOR", "message": "x",
                         "component": "c", "type": "CODE_SMELL", "status": "OPEN"}]}
        """.trimIndent()
        server.enqueue(MockResponse().setBody(single))

        val result = client.getIssuesWithPaging("proj")

        assertTrue(result.isSuccess)
        assertEquals(1, (result as ApiResult.Success).data.issues.size)
        assertEquals(1, server.requestCount, "should stop after the first page when total is satisfied")
    }

    @Test
    fun `getIssuesWithPaging returns partial data on mid-pagination error`() = runTest {
        val page1 = """
            {"paging": {"pageIndex": 1, "pageSize": 2, "total": 4},
             "issues": [{"key": "i1", "rule": "r", "severity": "MINOR", "message": "x",
                         "component": "c", "type": "BUG", "status": "OPEN"}]}
        """.trimIndent()
        server.enqueue(MockResponse().setBody(page1))
        server.enqueue(MockResponse().setResponseCode(503))

        val result = client.getIssuesWithPaging("proj")

        // Mid-pagination 503 → returns partial success (items from page 1)
        assertTrue(result.isSuccess)
        assertEquals(1, (result as ApiResult.Success).data.issues.size)
    }

    // ── F-8: Hotspots pagination ───────────────────────────────────────────

    @Test
    fun `getSecurityHotspots fetches all 3 pages and combines hotspots`() = runTest {
        fun hotspotPage(pageIndex: Int, key1: String, key2: String) = """
            {
              "paging": {"pageIndex": $pageIndex, "pageSize": 2, "total": 6},
              "hotspots": [
                {"key": "$key1", "message": "m", "component": "c:f",
                 "securityCategory": "sql-injection", "vulnerabilityProbability": "HIGH",
                 "status": "TO_REVIEW"},
                {"key": "$key2", "message": "m", "component": "c:f",
                 "securityCategory": "sql-injection", "vulnerabilityProbability": "HIGH",
                 "status": "TO_REVIEW"}
              ]
            }
        """.trimIndent()
        server.enqueue(MockResponse().setBody(hotspotPage(1, "h1", "h2")))
        server.enqueue(MockResponse().setBody(hotspotPage(2, "h3", "h4")))
        server.enqueue(MockResponse().setBody(hotspotPage(3, "h5", "h6")))

        val result = client.getSecurityHotspots("proj")

        assertTrue(result.isSuccess)
        val data = (result as ApiResult.Success).data
        assertEquals(6, data.hotspots.size)
        assertEquals(listOf("h1","h2","h3","h4","h5","h6"), data.hotspots.map { it.key })
        assertEquals(3, server.requestCount)
        val paths = (1..3).map { server.takeRequest().path!! }
        assertTrue(paths[0].contains("p=1"), "page 1 path: ${paths[0]}")
        assertTrue(paths[1].contains("p=2"), "page 2 path: ${paths[1]}")
        assertTrue(paths[2].contains("p=3"), "page 3 path: ${paths[2]}")
    }

    @Test
    fun `getSecurityHotspots stops when all items accumulated before max pages`() = runTest {
        val single = """
            {"paging": {"pageIndex": 1, "pageSize": 500, "total": 1},
             "hotspots": [{"key": "only", "message": "x", "component": "c:f",
                           "securityCategory": "xss", "vulnerabilityProbability": "MEDIUM",
                           "status": "TO_REVIEW"}]}
        """.trimIndent()
        server.enqueue(MockResponse().setBody(single))

        val result = client.getSecurityHotspots("proj")

        assertTrue(result.isSuccess)
        assertEquals(1, (result as ApiResult.Success).data.hotspots.size)
        assertEquals(1, server.requestCount)
    }
}

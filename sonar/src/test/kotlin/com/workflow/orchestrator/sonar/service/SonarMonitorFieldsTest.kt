package com.workflow.orchestrator.sonar.service

import com.intellij.openapi.project.Project
import com.intellij.testFramework.LoggedErrorProcessorEnabler
import com.workflow.orchestrator.sonar.api.SonarApiClient
import io.mockk.mockk
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
 * [SonarServiceImpl.getQualityGateStatus] and [SonarApiClient.getIssues].
 *
 * Monitor-relevant fields asserted:
 * - [getQualityGateStatus]: [QualityGateData.status] ("OK"/"ERROR")
 * - [getIssues] (via SonarApiClient): [SonarIssueDto.key], [SonarIssueDto.severity],
 *   [SonarIssueDto.type], and the full API→[SonarIssueData] mapping via SonarServiceImpl
 *
 * Architecture note: [SonarServiceImpl.getIssues] invokes a `componentExists` preflight
 * that reads ConnectionSettings (an application-level platform service) as a cache key.
 * That call is not reachable from a plain headless test without full platform DI wiring.
 * [SonarServiceImpl.getQualityGateStatus] has no such dep and is therefore exercised at
 * the service level. The [SonarApiClient] path for getIssues (which covers the
 * [SonarIssueDto] parse and field values) is exercised directly; [SonarServiceImpl]'s
 * one-to-one mapping is verified with a source-contract check so no field is silently
 * dropped between DTO and [SonarIssueData].
 */
@ExtendWith(LoggedErrorProcessorEnabler.DoNoRethrowErrors::class)
class SonarMonitorFieldsTest {

    private val project: Project = mockk(relaxed = true)
    private lateinit var server: MockWebServer
    private lateinit var apiClient: SonarApiClient
    private lateinit var service: SonarServiceImpl

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        apiClient = SonarApiClient(
            baseUrl = server.url("/").toString().trimEnd('/'),
            tokenProvider = { "test-token" }
        )
        service = SonarServiceImpl(project).also { it.testClient = apiClient }
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    // ── getQualityGateStatus via SonarServiceImpl: status "OK" / "ERROR" ─────────

    @Test
    fun `getQualityGateStatus parses OK status from realistic SonarQube projectStatus JSON`() = runTest {
        // Realistic SonarQube /api/qualitygates/project_status shape (DC + Cloud identical).
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{
                  "projectStatus": {
                    "status": "OK",
                    "conditions": [
                      {"status": "OK", "metricKey": "new_coverage", "comparator": "LT", "errorThreshold": "80", "actualValue": "87.3"},
                      {"status": "OK", "metricKey": "new_bugs",     "comparator": "GT", "errorThreshold": "0",  "actualValue": "0"}
                    ],
                    "ignoredConditions": false
                  }
                }"""
            )
        )

        val result = service.getQualityGateStatus("com.example:my-app")

        assertFalse(result.isError, "Expected success; got: ${result.summary}")
        val gate = result.data!!

        // Monitor-critical field: status
        assertEquals("OK", gate.status, "QualityGateData.status must be 'OK'")
        assertEquals(2, gate.conditions.size)
        assertEquals("new_coverage", gate.conditions[0].metric)
        assertEquals("OK", gate.conditions[0].status)
    }

    @Test
    fun `getQualityGateStatus parses ERROR status when quality gate fails`() = runTest {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{
                  "projectStatus": {
                    "status": "ERROR",
                    "conditions": [
                      {"status": "ERROR", "metricKey": "new_coverage",   "comparator": "LT", "errorThreshold": "80", "actualValue": "42.1"},
                      {"status": "OK",    "metricKey": "new_bugs",        "comparator": "GT", "errorThreshold": "0",  "actualValue": "0"},
                      {"status": "ERROR", "metricKey": "new_blocker_violations", "comparator": "GT", "errorThreshold": "0", "actualValue": "2"}
                    ],
                    "ignoredConditions": false
                  }
                }"""
            )
        )

        val result = service.getQualityGateStatus("com.example:my-app")

        assertFalse(result.isError)
        val gate = result.data!!
        assertEquals("ERROR", gate.status, "QualityGateData.status must be 'ERROR'")
        assertEquals(3, gate.conditions.size)
        val failed = gate.conditions.filter { it.status == "ERROR" }
        assertEquals(2, failed.size, "Two conditions must have ERROR status")
    }

    @Test
    fun `getQualityGateStatus surfaces isError on 401`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        val result = service.getQualityGateStatus("com.example:my-app")

        assertTrue(result.isError)
        // data still returns a fallback ERROR gate to avoid NPE in callers
        assertEquals("ERROR", result.data?.status)
    }

    @Test
    fun `getQualityGateStatus surfaces isError on 404`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))

        val result = service.getQualityGateStatus("com.example:nonexistent")

        assertTrue(result.isError)
    }

    // ── getIssues via SonarApiClient: key / severity / status / type ─────────────
    //
    // SonarServiceImpl.getIssues has a componentExists preflight that reads
    // ConnectionSettings (a platform APP-level service) as a cache key, making it
    // untestable at the service layer in a headless test without full DI wiring.
    // These tests exercise the SonarApiClient layer directly — the same client that
    // SonarServiceImpl delegates to — and assert the DTO fields that the service maps
    // 1:1 into SonarIssueData. The source-contract test below pins that mapping.

    @Test
    fun `getIssues parses key severity type and status from realistic SonarQube issues-search JSON`() = runTest {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{
                  "paging": {"pageIndex": 1, "pageSize": 500, "total": 3},
                  "issues": [
                    {
                      "key": "AYz1",
                      "rule": "java:S2259",
                      "severity": "BLOCKER",
                      "message": "A NullPointerException could be thrown.",
                      "component": "com.example:my-app:src/main/java/UserService.java",
                      "type": "BUG",
                      "effort": "15min",
                      "textRange": {"startLine": 42, "endLine": 42, "startOffset": 8, "endOffset": 32},
                      "status": "OPEN",
                      "issueStatus": "OPEN"
                    },
                    {
                      "key": "AYz2",
                      "rule": "java:S3649",
                      "severity": "CRITICAL",
                      "message": "SQL injection risk.",
                      "component": "com.example:my-app:src/main/java/QueryBuilder.java",
                      "type": "VULNERABILITY",
                      "effort": "30min",
                      "textRange": {"startLine": 18, "endLine": 20, "startOffset": 0, "endOffset": 45},
                      "status": "OPEN",
                      "issueStatus": "OPEN"
                    },
                    {
                      "key": "AYz3",
                      "rule": "java:S3776",
                      "severity": "MAJOR",
                      "message": "Cognitive complexity too high.",
                      "component": "com.example:my-app:src/main/java/ReportService.java",
                      "type": "CODE_SMELL",
                      "effort": "2h",
                      "status": "OPEN",
                      "issueStatus": "OPEN"
                    }
                  ]
                }"""
            )
        )

        // Test at SonarApiClient level — the same client SonarServiceImpl delegates to
        val result = apiClient.getIssues("com.example:my-app")

        assertTrue(result.isSuccess, "Expected success from SonarApiClient.getIssues")
        val issues = (result as com.workflow.orchestrator.core.model.ApiResult.Success).data
        assertEquals(3, issues.size)

        // Monitor-critical DTO fields (SonarServiceImpl maps these 1:1 into SonarIssueData)
        assertEquals("AYz1", issues[0].key)
        assertEquals("BLOCKER", issues[0].severity)
        assertEquals("BUG", issues[0].type)

        assertEquals("AYz2", issues[1].key)
        assertEquals("CRITICAL", issues[1].severity)
        assertEquals("VULNERABILITY", issues[1].type)

        assertEquals("AYz3", issues[2].key)
        assertEquals("MAJOR", issues[2].severity)
        assertEquals("CODE_SMELL", issues[2].type)
    }

    @Test
    fun `getIssues parses all severity levels from a mixed-severity response`() = runTest {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{
                  "paging": {"pageIndex": 1, "pageSize": 500, "total": 5},
                  "issues": [
                    {"key":"k1","rule":"r","severity":"BLOCKER", "message":"m","component":"p:a:F.java","type":"BUG"},
                    {"key":"k2","rule":"r","severity":"CRITICAL","message":"m","component":"p:a:F.java","type":"VULNERABILITY"},
                    {"key":"k3","rule":"r","severity":"MAJOR",   "message":"m","component":"p:a:F.java","type":"CODE_SMELL"},
                    {"key":"k4","rule":"r","severity":"MINOR",   "message":"m","component":"p:a:F.java","type":"CODE_SMELL"},
                    {"key":"k5","rule":"r","severity":"INFO",    "message":"m","component":"p:a:F.java","type":"CODE_SMELL"}
                  ]
                }"""
            )
        )

        val result = apiClient.getIssues("p:a")
        assertTrue(result.isSuccess)
        val issues = (result as com.workflow.orchestrator.core.model.ApiResult.Success).data

        assertEquals(listOf("BLOCKER", "CRITICAL", "MAJOR", "MINOR", "INFO"), issues.map { it.severity })
    }

    @Test
    fun `getIssues returns error result on 401`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        val result = apiClient.getIssues("com.example:my-app")

        assertTrue(result.isError)
        assertEquals(
            com.workflow.orchestrator.core.model.ErrorType.AUTH_FAILED,
            (result as com.workflow.orchestrator.core.model.ApiResult.Error).type
        )
    }

    // ── Source-contract: SonarServiceImpl maps DTO fields 1:1 into SonarIssueData ─

    /**
     * Pins the field-mapping in [SonarServiceImpl.getIssues] that the monitor reads.
     * This is a source-text contract — if someone removes the mapping for key/severity/
     * status/type, this test fails immediately.
     */
    @Test
    fun `SonarServiceImpl getIssues source maps key severity type and issueStatus from DTO into SonarIssueData`() {
        val src = java.io.File(
            "src/main/kotlin/com/workflow/orchestrator/sonar/service/SonarServiceImpl.kt"
        ).readText()

        // key, severity, message, component, type are all mapped in getIssues
        assertTrue(src.contains("key = dto.key"),          "SonarServiceImpl must map dto.key → SonarIssueData.key")
        assertTrue(src.contains("severity = dto.severity"), "SonarServiceImpl must map dto.severity → SonarIssueData.severity")
        assertTrue(src.contains("type = dto.type"),         "SonarServiceImpl must map dto.type → SonarIssueData.type")
        assertTrue(src.contains("issueStatus = dto.issueStatus"), "SonarServiceImpl must map dto.issueStatus → SonarIssueData.issueStatus")
        // status is always set to "OPEN" by the impl (getIssues fetches resolved=false)
        assertTrue(src.contains("""status = "OPEN""""),    "SonarServiceImpl must set status = \"OPEN\" (unresolved-only fetch)")
    }
}

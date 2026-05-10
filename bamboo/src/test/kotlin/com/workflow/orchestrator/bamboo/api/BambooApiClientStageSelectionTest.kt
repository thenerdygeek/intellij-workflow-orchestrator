package com.workflow.orchestrator.bamboo.api

import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
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
 * Stage-picker tests for [BambooApiClient.queueBuildWithStageSelection] — C-faithful implementation.
 *
 * Validates that:
 *  - null selectedStages → REST `executeAllStages=true` path (explicit "run everything" escape hatch)
 *  - non-null non-empty set → C-faithful action endpoint `/build/admin/ajax/runChainAction.action`
 *    with `stages_<name>=true` form fields and `X-Atlassian-Token: no-check` header
 *  - empty set → validation error before any network call
 *  - build variables are forwarded correctly as `bamboo.variable.<k>=<v>` form fields
 *  - after successful action POST: `getRunningAndQueuedBuilds` called to find the queued entry
 *
 * See `docs/architecture/automation-stage-picker-c-faithful-plan.md` Phase H1 + H8.
 */
class BambooApiClientStageSelectionTest {

    private lateinit var server: MockWebServer
    private lateinit var client: BambooApiClient

    private val queuedBuildResponse = """
        {"buildResultKey":"PROJ-BUILD-42","buildNumber":42,"planKey":"PROJ-BUILD"}
    """.trimIndent()

    // Running/queued builds response used after the action POST to find the queued entry.
    private val runningBuildsResponse = """
        {
          "results": {
            "result": [
              {
                "key": "PROJ-BUILD-43",
                "buildResultKey": "PROJ-BUILD-43",
                "buildNumber": 43,
                "state": "Unknown",
                "lifeCycleState": "Queued",
                "buildDurationInSeconds": 0,
                "buildRelativeTime": "",
                "stages": {"size": 0, "stage": []},
                "variables": {"size": 0, "variable": []}
              }
            ]
          }
        }
    """.trimIndent()

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

    // ==================== null selectedStages → executeAllStages=true (REST path) ====================

    @Test
    fun `null selectedStages sends executeAllStages=true via REST queue endpoint`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(queuedBuildResponse))

        val result = client.queueBuildWithStageSelection("PROJ-BUILD", emptyMap(), null)

        assertTrue(result.isSuccess, "Expected success, got $result")
        val recorded = server.takeRequest()
        assertTrue(
            recorded.path!!.contains("executeAllStages=true"),
            "Expected executeAllStages=true in path, got: ${recorded.path}"
        )
        assertFalse(
            recorded.path!!.contains("stage="),
            "Should not have a stage= param when stages=null, got: ${recorded.path}"
        )
        // REST path should use /rest/api/latest/queue/, NOT the action endpoint
        assertTrue(
            recorded.path!!.contains("/rest/api/latest/queue/"),
            "Expected REST queue path, got: ${recorded.path}"
        )
    }

    @Test
    fun `null selectedStages request includes X-Atlassian-Token header`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(queuedBuildResponse))

        client.queueBuildWithStageSelection("PROJ-BUILD", emptyMap(), null)

        val recorded = server.takeRequest()
        assertEquals("no-check", recorded.getHeader("X-Atlassian-Token"),
            "X-Atlassian-Token: no-check header required for Bamboo DC XSRF bypass")
    }

    // ==================== empty set → validation error ====================

    @Test
    fun `empty selectedStages returns validation error without network call`() = runTest {
        val result = client.queueBuildWithStageSelection("PROJ-BUILD", emptyMap(), emptySet())

        assertTrue(result.isError, "Expected error for empty stages")
        assertTrue(result is ApiResult.Error && result.type == ErrorType.VALIDATION_ERROR,
            "Expected VALIDATION_ERROR, got: $result")
        assertEquals(0, server.requestCount, "No network call should be made for empty stages")
    }

    // ==================== C-faithful action endpoint ====================

    @Test
    fun `non-null selectedStages POSTs to action endpoint with stages_name=true form fields`() = runTest {
        // Action POST returns 200, then getRunningAndQueuedBuilds returns the queued entry
        server.enqueue(MockResponse().setResponseCode(200).setBody("<!-- action ok -->"))
        server.enqueue(MockResponse().setResponseCode(200).setBody(runningBuildsResponse))

        val stages = setOf("Build", "Deploy")
        val result = client.queueBuildWithStageSelection("PROJ-BUILD", emptyMap(), stages)

        assertTrue(result.isSuccess, "Expected success, got $result")

        // First request must be the action endpoint
        val actionRequest = server.takeRequest()
        assertTrue(
            actionRequest.path!!.contains("/build/admin/ajax/runChainAction.action"),
            "Expected action endpoint, got: ${actionRequest.path}"
        )
        // Should NOT use the REST queue endpoint
        assertFalse(
            actionRequest.path!!.contains("/rest/api/latest/queue/"),
            "C-faithful must not use REST queue path, got: ${actionRequest.path}"
        )

        // Form body must contain stages_<name>=true for each selected stage
        val body = actionRequest.body.readUtf8()
        assertTrue(body.contains("stages_Build=true"),
            "Expected stages_Build=true in body, got: $body")
        assertTrue(body.contains("stages_Deploy=true"),
            "Expected stages_Deploy=true in body, got: $body")
        // planKey must be present
        assertTrue(body.contains("planKey=PROJ-BUILD") || body.contains("planKey=PROJ%2DBUILD"),
            "Expected planKey in body, got: $body")

        // Second request must be the getRunningAndQueuedBuilds lookup
        val lookupRequest = server.takeRequest()
        assertTrue(
            lookupRequest.path!!.contains("/rest/api/latest/result/"),
            "Expected running/queued builds lookup, got: ${lookupRequest.path}"
        )
    }

    @Test
    fun `action endpoint request has X-Atlassian-Token header`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("<!-- action ok -->"))
        server.enqueue(MockResponse().setResponseCode(200).setBody(runningBuildsResponse))

        client.queueBuildWithStageSelection("PROJ-BUILD", emptyMap(), setOf("Build"))

        val actionRequest = server.takeRequest()
        assertEquals("no-check", actionRequest.getHeader("X-Atlassian-Token"),
            "X-Atlassian-Token: no-check required on action endpoint")
    }

    @Test
    fun `C-faithful path does not contain executeAllStages=false or stage= in URL`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("<!-- action ok -->"))
        server.enqueue(MockResponse().setResponseCode(200).setBody(runningBuildsResponse))

        client.queueBuildWithStageSelection("PROJ-BUILD", emptyMap(), setOf("Build", "Unit Tests"))

        val actionRequest = server.takeRequest()
        assertFalse(
            actionRequest.path!!.contains("executeAllStages=false"),
            "C-faithful must not use executeAllStages=false (C-simple pattern), got: ${actionRequest.path}"
        )
        assertFalse(
            actionRequest.path!!.contains("stage="),
            "C-faithful must not use stage= param (C-simple pattern), got: ${actionRequest.path}"
        )
    }

    @Test
    fun `action POST followed by getRunningAndQueuedBuilds synthesises BambooQueueResponse`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("<!-- ok -->"))
        server.enqueue(MockResponse().setResponseCode(200).setBody(runningBuildsResponse))

        val result = client.queueBuildWithStageSelection("PROJ-BUILD", emptyMap(), setOf("Build"))

        assertTrue(result.isSuccess, "Expected success, got $result")
        val response = (result as ApiResult.Success).data
        assertEquals("PROJ-BUILD-43", response.buildResultKey)
        assertEquals(43, response.buildNumber)
        assertEquals(2, server.requestCount, "Expected 2 requests: action POST + queue lookup")
    }

    @Test
    fun `action endpoint failure is surfaced as error without fallback`() = runTest {
        server.enqueue(MockResponse().setResponseCode(403).setBody("Forbidden"))

        val result = client.queueBuildWithStageSelection("PROJ-BUILD", emptyMap(), setOf("Build"))

        assertTrue(result.isError, "Expected error when action endpoint returns 403")
        // No second request should be made (no fallback attempted)
        assertEquals(1, server.requestCount, "Should stop after action endpoint failure")
    }

    @Test
    fun `action endpoint 403 returns FORBIDDEN error type`() = runTest {
        server.enqueue(MockResponse().setResponseCode(403).setBody("Forbidden"))

        val result = client.queueBuildWithStageSelection("PROJ-BUILD", emptyMap(), setOf("Build"))

        assertTrue(result is ApiResult.Error, "Expected ApiResult.Error for 403")
        assertEquals(
            ErrorType.FORBIDDEN,
            (result as ApiResult.Error).type,
            "403 from action endpoint must map to FORBIDDEN, not a generic error"
        )
        assertEquals(1, server.requestCount, "No follow-up call after 403")
    }

    @Test
    fun `action endpoint 401 returns AUTH_FAILED error type`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("Unauthorized"))

        val result = client.queueBuildWithStageSelection("PROJ-BUILD", emptyMap(), setOf("Build"))

        assertTrue(result is ApiResult.Error, "Expected ApiResult.Error for 401")
        assertEquals(
            ErrorType.AUTH_FAILED,
            (result as ApiResult.Error).type,
            "401 from action endpoint must map to AUTH_FAILED"
        )
        assertEquals(1, server.requestCount, "No follow-up call after 401")
    }

    // NOTE: A test for 500 → SERVER_ERROR is intentionally omitted here.
    // RetryInterceptor retries 5xx codes up to 3 times and calls log.error() on
    // exhaustion. IntelliJ's TestLoggerFactory turns log.error() into a test failure,
    // so any test that exercises the 500 retry path will always fail in the IntelliJ
    // test runner regardless of the returned ApiResult. The 500 → SERVER_ERROR mapping
    // is exercised implicitly: BambooApiClient's `get` helper maps every non-retried
    // 5xx to SERVER_ERROR using the same `else ->` branch, and that path is tested by
    // BambooApiClientTest. The 403 and 401 tests above lock in the error-type contract
    // for the non-retryable action-endpoint failures.

    // ==================== Redirect handling (Blocker 1 fix) ====================

    @Test
    fun `action endpoint 302 redirect to non-login Location is treated as success`() = runTest {
        // Bamboo's Struts action returns 302 → /build/admin/dashboard on success.
        // This must NOT be treated as AUTH_REDIRECT — only auth redirects (Location
        // containing /login, userlogin, permissionViolation, usernotloggedin) are errors.
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .addHeader("Location", "/build/admin/dashboard")
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody(runningBuildsResponse))

        val result = client.queueBuildWithStageSelection("PROJ-BUILD", emptyMap(), setOf("Build"))

        assertTrue(result.isSuccess,
            "302 redirect to /build/admin/dashboard should be treated as Struts-success, got $result")
        assertEquals(2, server.requestCount,
            "Expected 2 requests: action POST (302) + queue lookup")
    }

    @Test
    fun `action endpoint 302 redirect to login page returns AUTH_REDIRECT error`() = runTest {
        // A genuine auth-expiry redirect has Location pointing to the login page.
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .addHeader("Location", "/userlogin.action?permissionViolation=true")
        )

        val result = client.queueBuildWithStageSelection("PROJ-BUILD", emptyMap(), setOf("Build"))

        assertTrue(result is ApiResult.Error, "Expected error for auth redirect")
        assertEquals(
            ErrorType.AUTH_REDIRECT,
            (result as ApiResult.Error).type,
            "302 to login must map to AUTH_REDIRECT"
        )
        // No second request — auth failure is terminal
        assertEquals(1, server.requestCount, "No queue lookup after auth redirect")
    }

    // ==================== Variables forwarded correctly ====================

    @Test
    fun `build variables are included in action endpoint form body`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("<!-- ok -->"))
        server.enqueue(MockResponse().setResponseCode(200).setBody(runningBuildsResponse))

        val vars = mapOf("myVar" to "hello", "otherVar" to "world")
        client.queueBuildWithStageSelection("PROJ-BUILD", vars, setOf("Build"))

        val actionRequest = server.takeRequest()
        val body = actionRequest.body.readUtf8()
        assertTrue(
            body.contains("bamboo.variable.myVar=hello"),
            "Expected myVar in form body, got: $body"
        )
        assertTrue(
            body.contains("bamboo.variable.otherVar=world"),
            "Expected otherVar in form body, got: $body"
        )
    }

    @Test
    fun `build variables in null stages (all-stages) path are included in form body`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(queuedBuildResponse))

        val vars = mapOf("myVar" to "hello", "otherVar" to "world")
        client.queueBuildWithStageSelection("PROJ-BUILD", vars, null)

        val recorded = server.takeRequest()
        val body = recorded.body.readUtf8()
        assertTrue(
            body.contains("bamboo.variable.myVar=hello"),
            "Expected myVar in form body, got: $body"
        )
        assertTrue(
            body.contains("bamboo.variable.otherVar=world"),
            "Expected otherVar in form body, got: $body"
        )
    }

    @Test
    fun `non-contiguous stage selection includes all stages as stages_name=true`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("<!-- ok -->"))
        server.enqueue(MockResponse().setResponseCode(200).setBody(runningBuildsResponse))

        // Non-contiguous: Build and Deploy (skipping Unit Tests)
        val stages = linkedSetOf("Build", "Deploy")
        client.queueBuildWithStageSelection("PROJ-BUILD", emptyMap(), stages)

        val actionRequest = server.takeRequest()
        val body = actionRequest.body.readUtf8()
        assertTrue(body.contains("stages_Build=true"), "Expected stages_Build=true, got: $body")
        assertTrue(body.contains("stages_Deploy=true"), "Expected stages_Deploy=true, got: $body")
        assertFalse(body.contains("stages_Unit"), "Should not have Unit Tests stage, got: $body")
    }

    @Test
    fun `single-stage set uses action endpoint not REST queue`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("<!-- ok -->"))
        server.enqueue(MockResponse().setResponseCode(200).setBody(runningBuildsResponse))

        client.queueBuildWithStageSelection("PROJ-BUILD", emptyMap(), setOf("Build"))

        val actionRequest = server.takeRequest()
        assertTrue(
            actionRequest.path!!.contains("/build/admin/ajax/runChainAction.action"),
            "Even a single-stage set must use the action endpoint (not REST), got: ${actionRequest.path}"
        )
    }
}

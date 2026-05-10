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
 * Stage-picker tests for [BambooApiClient.queueBuildWithStageSelection].
 *
 * **Wire contract (validated against Bamboo DC 10.2.14):**
 *  - `null` selectedStages → REST `?executeAllStages=true` path
 *  - non-null non-empty selection → REST `?executeAllStages=false&stage=<firstStage>`
 *    (Bamboo runs from that stage forward — single-stage param limit of REST API)
 *  - empty set → validation error before any network call
 *  - build variables forwarded as `bamboo.variable.<k>=<v>` form fields
 *  - `X-Atlassian-Token: no-check` header set on every POST (Bamboo DC XSRF bypass)
 *
 * **History.** A previous attempt to use the Struts action endpoint
 * `/build/admin/ajax/runChainAction.action` for arbitrary subset selection returned
 * 404 in production. The REST queue endpoint is the documented and validated path.
 * The "non-contiguous selection" capability is acknowledged as a future enhancement;
 * today the first selected stage drives the trigger and Bamboo runs forward from
 * there.
 */
class BambooApiClientStageSelectionTest {

    private lateinit var server: MockWebServer
    private lateinit var client: BambooApiClient

    private val queuedBuildResponse = """
        {"buildResultKey":"PROJ-BUILD-42","buildNumber":42,"planKey":"PROJ-BUILD"}
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

    // ==================== null selectedStages → executeAllStages=true ====================

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
        assertTrue(
            recorded.path!!.contains("/rest/api/latest/queue/PROJ-BUILD"),
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

    // ==================== non-null non-empty → REST queue with stage=<first> ====================

    @Test
    fun `non-null selectedStages POSTs to REST queue endpoint with stage=lastStageInPlanOrder`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(queuedBuildResponse))

        // LinkedHashSet preserves insertion order; production callers build the set
        // from the dialog's checkbox list which iterates in plan order. Bamboo's
        // `stage` param is the upper bound — the LAST plan-order stage the user
        // checked is what Bamboo should run TO (probe-verified 2026-05-10).
        val stages = linkedSetOf("Build", "Deploy")
        val result = client.queueBuildWithStageSelection("PROJ-BUILD", emptyMap(), stages)

        assertTrue(result.isSuccess, "Expected success, got $result")

        val recorded = server.takeRequest()
        assertTrue(
            recorded.path!!.contains("/rest/api/latest/queue/PROJ-BUILD"),
            "Expected REST queue path, got: ${recorded.path}"
        )
        assertTrue(
            recorded.path!!.contains("executeAllStages=false"),
            "Expected executeAllStages=false in path, got: ${recorded.path}"
        )
        // Last stage in iteration order is "Deploy" — that's the upper bound;
        // Bamboo runs Build then Deploy and stops.
        assertTrue(
            recorded.path!!.contains("stage=Deploy"),
            "Expected stage=Deploy (the LAST selected, which is the upper bound), got: ${recorded.path}"
        )
        assertFalse(
            recorded.path!!.contains("stage=Build"),
            "Must NOT send stage=Build (that was v0.84.10's bug — sending the first " +
                "selected made Bamboo stop after Build, ignoring Deploy). Got: ${recorded.path}"
        )
        // Should NOT use the Struts action endpoint (the path that 404'd in production).
        assertFalse(
            recorded.path!!.contains("/build/admin/ajax/runChainAction.action"),
            "Must not use Struts action endpoint, got: ${recorded.path}"
        )
    }

    @Test
    fun `three-stage selection uses the last-in-plan-order stage as the bound`() = runTest {
        // Locks in the .last() semantic against future regressions: with stages
        // {Build, Test, Deploy} all checked, Bamboo must be told to run UP TO Deploy
        // (not Build, which would only run the first stage).
        server.enqueue(MockResponse().setResponseCode(200).setBody(queuedBuildResponse))

        val stages = linkedSetOf("Build", "Test", "Deploy")
        client.queueBuildWithStageSelection("PROJ-BUILD", emptyMap(), stages)

        val recorded = server.takeRequest()
        assertTrue(
            recorded.path!!.contains("stage=Deploy"),
            "Three-stage selection must use the LAST stage as bound (Deploy), got: ${recorded.path}"
        )
        assertFalse(
            recorded.path!!.contains("stage=Build") || recorded.path!!.contains("stage=Test"),
            "Must NOT send Build or Test as the bound. Got: ${recorded.path}"
        )
    }

    @Test
    fun `single-stage selection uses REST queue with that stage`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(queuedBuildResponse))

        val result = client.queueBuildWithStageSelection("PROJ-BUILD", emptyMap(), setOf("Deploy"))

        assertTrue(result.isSuccess, "Expected success, got $result")
        val recorded = server.takeRequest()
        assertTrue(
            recorded.path!!.contains("/rest/api/latest/queue/PROJ-BUILD"),
            "Single-stage selection must use REST queue endpoint, got: ${recorded.path}"
        )
        assertTrue(
            recorded.path!!.contains("stage=Deploy"),
            "Expected stage=Deploy, got: ${recorded.path}"
        )
        assertEquals(1, server.requestCount, "Single REST call — no follow-up lookup needed")
    }

    @Test
    fun `stage names with spaces are URL-encoded in the stage query param`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(queuedBuildResponse))

        client.queueBuildWithStageSelection("PROJ-BUILD", emptyMap(), setOf("Unit Tests"))

        val recorded = server.takeRequest()
        // URLEncoder uses + for spaces in form-encoded query params.
        assertTrue(
            recorded.path!!.contains("stage=Unit+Tests") || recorded.path!!.contains("stage=Unit%20Tests"),
            "Expected URL-encoded stage name, got: ${recorded.path}"
        )
    }

    @Test
    fun `non-null selectedStages request includes X-Atlassian-Token header`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(queuedBuildResponse))

        client.queueBuildWithStageSelection("PROJ-BUILD", emptyMap(), setOf("Build"))

        val recorded = server.takeRequest()
        assertEquals("no-check", recorded.getHeader("X-Atlassian-Token"),
            "X-Atlassian-Token: no-check required on the REST queue endpoint")
    }

    // ==================== Error mapping ====================

    @Test
    fun `404 from queue endpoint returns NOT_FOUND error type`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("Not found"))

        val result = client.queueBuildWithStageSelection("PROJ-BUILD", emptyMap(), setOf("Build"))

        assertTrue(result is ApiResult.Error, "Expected ApiResult.Error for 404")
        assertEquals(
            ErrorType.NOT_FOUND,
            (result as ApiResult.Error).type,
            "404 from REST queue must map to NOT_FOUND so the user gets a useful hint"
        )
    }

    @Test
    fun `403 from queue endpoint returns FORBIDDEN error type`() = runTest {
        server.enqueue(MockResponse().setResponseCode(403).setBody("Forbidden"))

        val result = client.queueBuildWithStageSelection("PROJ-BUILD", emptyMap(), setOf("Build"))

        assertTrue(result is ApiResult.Error, "Expected ApiResult.Error for 403")
        assertEquals(
            ErrorType.FORBIDDEN,
            (result as ApiResult.Error).type,
            "403 from REST queue must map to FORBIDDEN, not a generic error"
        )
    }

    @Test
    fun `401 from queue endpoint returns AUTH_FAILED error type`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("Unauthorized"))

        val result = client.queueBuildWithStageSelection("PROJ-BUILD", emptyMap(), setOf("Build"))

        assertTrue(result is ApiResult.Error, "Expected ApiResult.Error for 401")
        assertEquals(
            ErrorType.AUTH_FAILED,
            (result as ApiResult.Error).type,
            "401 from REST queue must map to AUTH_FAILED"
        )
    }

    // ==================== Variables forwarded correctly ====================

    @Test
    fun `build variables are included in REST queue form body`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(queuedBuildResponse))

        val vars = mapOf("myVar" to "hello", "otherVar" to "world")
        client.queueBuildWithStageSelection("PROJ-BUILD", vars, setOf("Build"))

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
}

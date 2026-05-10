package com.workflow.orchestrator.bamboo.api

import com.workflow.orchestrator.core.model.ApiResult
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Stage-picker tests for [BambooApiClient.queueBuildWithStageSelection].
 *
 * Validates that:
 *  - null selectedStages → `executeAllStages=true` (run everything)
 *  - non-null set → `executeAllStages=false&stage=<firstStage>` (stage-filtered run)
 *  - empty set → validation error before any network call
 *  - build variables are forwarded correctly as form fields
 *
 * See `docs/architecture/automation-stage-picker-plan.md` Phase F1.
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

    @Test
    fun `null selectedStages sends executeAllStages=true`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(queuedBuildResponse))

        val result = client.queueBuildWithStageSelection("PROJ-BUILD", emptyMap(), null)

        assertTrue(result.isSuccess, "Expected success, got $result")
        val recorded = server.takeRequest()
        assertTrue(
            recorded.path!!.contains("executeAllStages=true"),
            "Expected executeAllStages=true in path, got: ${recorded.path}"
        )
        assertTrue(
            !recorded.path!!.contains("stage="),
            "Should not have a stage= param when stages=null, got: ${recorded.path}"
        )
    }

    @Test
    fun `non-null selectedStages sends executeAllStages=false and first stage name`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(queuedBuildResponse))

        val stages = linkedSetOf("Build", "Unit Tests")  // insertion-ordered
        val result = client.queueBuildWithStageSelection("PROJ-BUILD", emptyMap(), stages)

        assertTrue(result.isSuccess, "Expected success, got $result")
        val recorded = server.takeRequest()
        val path = recorded.path!!
        assertTrue(
            path.contains("executeAllStages=false"),
            "Expected executeAllStages=false in path, got: $path"
        )
        assertTrue(
            path.contains("stage=Build") || path.contains("stage=Build"),
            "Expected stage=Build in path (URL-encoded or plain), got: $path"
        )
    }

    @Test
    fun `empty selectedStages returns validation error without network call`() = runTest {
        val result = client.queueBuildWithStageSelection("PROJ-BUILD", emptyMap(), emptySet())

        assertTrue(result.isError, "Expected error for empty stages")
        assertEquals(0, server.requestCount, "No network call should be made for empty stages")
    }

    @Test
    fun `build variables are included in form body`() = runTest {
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
    fun `request includes X-Atlassian-Token header`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(queuedBuildResponse))

        client.queueBuildWithStageSelection("PROJ-BUILD", emptyMap(), null)

        val recorded = server.takeRequest()
        assertEquals("no-check", recorded.getHeader("X-Atlassian-Token"),
            "X-Atlassian-Token: no-check header required for Bamboo DC XSRF bypass")
    }

    @Test
    fun `single-stage set sends correct stage name URL-encoded`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(queuedBuildResponse))

        val stages = setOf("Deploy to Staging")
        client.queueBuildWithStageSelection("PROJ-BUILD", emptyMap(), stages)

        val recorded = server.takeRequest()
        val path = recorded.path!!
        // URL-encoded space is +  or %20; both are valid
        assertTrue(
            path.contains("stage=Deploy+to+Staging") || path.contains("stage=Deploy%20to%20Staging"),
            "Expected URL-encoded stage name, got: $path"
        )
    }
}

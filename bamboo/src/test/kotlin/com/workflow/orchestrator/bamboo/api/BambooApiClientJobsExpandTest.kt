package com.workflow.orchestrator.bamboo.api

import com.intellij.testFramework.LoggedErrorProcessorEnabler
import com.workflow.orchestrator.core.model.ApiResult
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Verifies that BambooApiClient.getBuildResult requests `?expand=stages.stage.results.result`
 * (the deep job expansion required for per-job log discoverability) and that the response
 * deserialises with populated `BambooStageDto.results.result[]` carrying `buildResultKey`.
 *
 * Sibling pattern: BambooApiClientPlanBranchTest.kt (MockWebServer + fixture-driven).
 */
@ExtendWith(LoggedErrorProcessorEnabler.DoNoRethrowErrors::class)
class BambooApiClientJobsExpandTest {

    private lateinit var server: MockWebServer
    private lateinit var client: BambooApiClient

    @BeforeEach
    fun setUp() {
        server = MockWebServer().also { it.start() }
        client = newClientFor(server)
    }

    @AfterEach
    fun tearDown() { server.shutdown() }

    @Test
    fun `getBuildResult URL requests stages stage results result expansion`() = runTest {
        val fixture = javaClass.getResourceAsStream("/fixtures/build-result.json")!!
            .bufferedReader().readText()
        server.enqueue(MockResponse().setResponseCode(200).setBody(fixture))

        val result = client.getBuildResult("PROJ-PLAN-42")

        assertTrue(result is ApiResult.Success, "Expected success, got: $result")
        val request = server.takeRequest()
        val path = request.path ?: ""
        assertTrue(path.contains("expand=stages.stage.results.result"),
            "URL must request the deep job expansion. Got: $path")
    }

    @Test
    fun `getBuildResult deserialises stages with non-empty results result`() = runTest {
        val fixture = javaClass.getResourceAsStream("/fixtures/build-result.json")!!
            .bufferedReader().readText()
        server.enqueue(MockResponse().setResponseCode(200).setBody(fixture))

        val result = client.getBuildResult("PROJ-PLAN-42")

        assertTrue(result is ApiResult.Success)
        val dto = (result as ApiResult.Success).data
        assertTrue(dto.stages.stage.isNotEmpty(), "Fixture should have at least one stage")
        val firstStage = dto.stages.stage.first()
        assertTrue(firstStage.results.result.isNotEmpty(),
            "Fixture must include results.result so per-job tests have data; update fixtures/build-result.json")
        assertTrue(firstStage.results.result.first().buildResultKey.isNotBlank(),
            "Each job must have a buildResultKey")
    }

    /**
     * Mirrors BambooApiClientPlanBranchTest's setUp() construction exactly.
     */
    private fun newClientFor(server: MockWebServer): BambooApiClient =
        BambooApiClient(
            baseUrl = server.url("/").toString().trimEnd('/'),
            tokenProvider = { "test-token" }
        )
}

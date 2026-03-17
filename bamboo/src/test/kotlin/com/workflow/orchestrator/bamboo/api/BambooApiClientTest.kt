package com.workflow.orchestrator.bamboo.api

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
class BambooApiClientTest {

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

    @Test
    fun `getPlans returns parsed plan list`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("plan-list.json")))

        val result = client.getPlans()

        assertTrue(result.isSuccess)
        val plans = (result as ApiResult.Success).data
        assertEquals(2, plans.size)
        assertEquals("PROJ-BUILD", plans[0].key)
        assertEquals("My Project - Build", plans[0].name)

        val recorded = server.takeRequest()
        assertEquals("/rest/api/latest/plan?expand=plans.plan&max-results=100", recorded.path)
        assertEquals("Bearer test-token", recorded.getHeader("Authorization"))
    }

    @Test
    fun `searchPlans returns search results`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("search-results.json")))

        val result = client.searchPlans("Build")

        assertTrue(result.isSuccess)
        val items = (result as ApiResult.Success).data
        assertEquals(2, items.size)
        assertEquals("PROJ-BUILD", items[0].key)

        val recorded = server.takeRequest()
        assertTrue(recorded.path!!.contains("/rest/api/latest/search/plans"))
        assertTrue(recorded.path!!.contains("searchTerm=Build"))
    }

    @Test
    fun `getLatestResult returns build result with stages`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("build-result.json")))

        val result = client.getLatestResult("PROJ-BUILD", "feature/PROJ-123")

        assertTrue(result.isSuccess)
        val build = (result as ApiResult.Success).data
        assertEquals(42, build.buildNumber)
        assertEquals("Successful", build.state)
        assertEquals(3, build.stages.stage.size)
        assertEquals("Compile", build.stages.stage[0].name)
        assertTrue(build.stages.stage[2].manual)

        val recorded = server.takeRequest()
        assertTrue(recorded.path!!.contains("/rest/api/latest/result/PROJ-BUILD/branch/feature%2FPROJ-123"))
    }

    @Test
    fun `getVariables returns plan variables`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("plan-variables.json")))

        val result = client.getVariables("PROJ-BUILD")

        assertTrue(result.isSuccess)
        val vars = (result as ApiResult.Success).data
        assertEquals(3, vars.size)
        assertEquals("skipTests", vars[0].name)
        assertEquals("false", vars[0].value)

        val recorded = server.takeRequest()
        assertEquals("/rest/api/latest/plan/PROJ-BUILD/variable", recorded.path)
    }

    @Test
    fun `triggerBuild sends POST with variables`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"buildResultKey":"PROJ-BUILD-44","buildNumber":44}"""))

        val variables = mapOf("skipTests" to "true", "deployTarget" to "prod")
        val result = client.triggerBuild("PROJ-BUILD", variables, stageName = "Deploy")

        assertTrue(result.isSuccess)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertTrue(recorded.path!!.contains("/rest/api/latest/queue/PROJ-BUILD"))
        assertTrue(recorded.path!!.contains("stage=Deploy"))
        assertTrue(recorded.path!!.contains("executeAllStages=false"))
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("skipTests"))
        assertTrue(body.contains("true"))
    }

    @Test
    fun `returns AUTH_FAILED on 401`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        val result = client.getPlans()

        assertTrue(result.isError)
        assertEquals(ErrorType.AUTH_FAILED, (result as ApiResult.Error).type)
    }

    @Test
    fun `getBuildLog returns raw log text`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("build-log.txt")))

        val result = client.getBuildLog("PROJ-BUILD-42")

        assertTrue(result.isSuccess)
        val log = (result as ApiResult.Success).data
        assertTrue(log.contains("[ERROR]"))
        assertTrue(log.contains("UserService.java"))

        val recorded = server.takeRequest()
        assertEquals("/download/PROJ-BUILD-42/build_logs/PROJ-BUILD-42.log", recorded.path)
    }

    @Test
    fun `getRunningAndQueuedBuilds returns filtered active builds`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("build-status-list.json")))

        val result = client.getRunningAndQueuedBuilds("PROJ-AUTO")

        assertTrue(result.isSuccess)
        val builds = (result as ApiResult.Success).data
        assertEquals(2, builds.size)
        assertTrue(builds.all { it.lifeCycleState in listOf("InProgress", "Queued", "Pending") })

        val recorded = server.takeRequest()
        assertTrue(recorded.path!!.contains("/rest/api/latest/result/PROJ-AUTO"))
        assertTrue(recorded.path!!.contains("includeAllStates=true"))
    }

    @Test
    fun `getBuildVariables returns variable map from build result`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("build-variables.json")))

        val result = client.getBuildVariables("PROJ-AUTO-847")

        assertTrue(result.isSuccess)
        val vars = (result as ApiResult.Success).data
        assertEquals("regression", vars["suiteType"])
        assertTrue(vars.containsKey("dockerTagsAsJson"))

        val recorded = server.takeRequest()
        assertTrue(recorded.path!!.contains("/rest/api/latest/result/PROJ-AUTO-847"))
        assertTrue(recorded.path!!.contains("expand=variables"))
    }

    @Test
    fun `getRecentResults returns last N build results`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("recent-results.json")))

        val result = client.getRecentResults("PROJ-AUTO", maxResults = 5)

        assertTrue(result.isSuccess)
        val results = (result as ApiResult.Success).data
        assertTrue(results.size <= 5)

        val recorded = server.takeRequest()
        assertTrue(recorded.path!!.contains("/rest/api/latest/result/PROJ-AUTO"))
        assertTrue(recorded.path!!.contains("max-results=5"))
    }

    @Test
    fun `cancelBuild sends DELETE and returns success`() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))

        val result = client.cancelBuild("PROJ-AUTO-847")

        assertTrue(result.isSuccess)

        val recorded = server.takeRequest()
        assertEquals("DELETE", recorded.method)
        assertTrue(recorded.path!!.contains("/rest/api/latest/queue/PROJ-AUTO-847"))
    }

    @Test
    fun `cancelBuild returns error on 404`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))

        val result = client.cancelBuild("PROJ-AUTO-999")

        assertTrue(result is ApiResult.Error)
        assertEquals(ErrorType.NOT_FOUND, (result as ApiResult.Error).type)
    }
}

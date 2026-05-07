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

    /**
     * §8.1 regression: Bamboo 10.2 variableContext uses `key`/`value` shape (NOT `name`/`value`).
     * Response shape validated against bundle-repo.unpacked/raw/plan_variables_via_context.json.
     */
    @Test
    fun `getPlanVariableContext parses Bamboo 10_2 key shape correctly`() = runTest {
        server.enqueue(MockResponse().setBody("""
            {
              "key": "PROJ-BUILD",
              "name": "Build Plan",
              "variableContext": {
                "size": 3,
                "variable": [
                  { "key": "JENKINS_SECRET", "variableType": "GLOBAL", "isPassword": true },
                  { "key": "DEVELOP_VERSION", "value": "1.2.3", "variableType": "PLAN", "isPassword": false },
                  { "key": "NO_PROXY", "value": "localhost", "variableType": "GLOBAL", "isPassword": false }
                ]
              }
            }
        """.trimIndent()))

        val result = client.getPlanVariableContext("PROJ-BUILD")

        assertTrue(result.isSuccess)
        val vars = (result as ApiResult.Success).data
        assertEquals(3, vars.size)
        assertEquals("JENKINS_SECRET", vars[0].key)
        assertEquals("", vars[0].value)  // password field — no value returned
        assertTrue(vars[0].isPassword)
        assertEquals("DEVELOP_VERSION", vars[1].key)
        assertEquals("1.2.3", vars[1].value)
        assertEquals("PLAN", vars[1].variableType)
        assertEquals("NO_PROXY", vars[2].key)

        val recorded = server.takeRequest()
        assertEquals("/rest/api/latest/plan/PROJ-BUILD?expand=variableContext", recorded.path)
    }

    @Test
    fun `queueBuild sends form-encoded POST with bamboo dot variable pairs`() = runTest {
        // Wire shape validated 2026-05-07 against Bamboo DC 10.2.14 via
        // tools/atlassian-probe/probe_bamboo.py --write-test. Bamboo silently dropped
        // every variable when sent JSON; form-encoded `bamboo.variable.X=Y` is the
        // only shape the queue endpoint actually honors.
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"buildResultKey":"PROJ-BUILD-44","buildNumber":44}"""))

        val variables = mapOf("skipTests" to "true", "deployTarget" to "prod")
        val result = client.queueBuild("PROJ-BUILD", variables, stageName = "Deploy")

        assertTrue(result.isSuccess)
        val qr = (result as ApiResult.Success).data
        assertEquals("PROJ-BUILD-44", qr.buildResultKey)
        assertEquals(44, qr.buildNumber)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        // URL: /rest/api/latest/queue/PROJ-BUILD with executeAllStages + stage in query string
        assertTrue(
            recorded.path!!.startsWith("/rest/api/latest/queue/PROJ-BUILD?"),
            "Expected queue/{planKey}? prefix, got ${recorded.path}"
        )
        assertTrue(recorded.path!!.contains("executeAllStages=false"))
        assertTrue(recorded.path!!.contains("stage=Deploy"))
        // Content-Type and XSRF header (set automatically by postForm)
        val contentType = recorded.getHeader("Content-Type") ?: ""
        assertTrue(
            contentType.startsWith("application/x-www-form-urlencoded"),
            "Expected application/x-www-form-urlencoded, got '$contentType'"
        )
        assertEquals("no-check", recorded.getHeader("X-Atlassian-Token"))
        // Body is form-encoded `bamboo.variable.<k>=<v>` pairs
        val body = recorded.body.readUtf8()
        val pairs = body.split("&").toSet()
        assertEquals(
            setOf("bamboo.variable.skipTests=true", "bamboo.variable.deployTarget=prod"),
            pairs
        )
    }

    @Test
    fun `queueBuild URL-encodes special characters in variable values`() = runTest {
        // dockerTagsAsJson values are JSON literals like {"svc":"1.2.3"} — braces, quotes,
        // colons all need URL-encoding. Round-trip must reach the server intact.
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"buildResultKey":"PROJ-AUTO-101","buildNumber":101}"""))

        val payload = """{"svc":"1.2.3","env":"prod"}"""
        val result = client.queueBuild("PROJ-AUTO", mapOf("dockerTagsAsJson" to payload))

        assertTrue(result.isSuccess)
        val recorded = server.takeRequest()
        // Decoded body must contain the exact original payload + key — proves URL-encoding round-trip.
        val decoded = java.net.URLDecoder.decode(recorded.body.readUtf8(), "UTF-8")
        assertEquals("bamboo.variable.dockerTagsAsJson=$payload", decoded)
        // executeAllStages defaults to true when no stage is provided
        assertTrue(recorded.path!!.contains("executeAllStages=true"))
        assertFalse(recorded.path!!.contains("stage="))
    }

    @Test
    fun `queueBuild URL-encodes stage name in query string`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"buildResultKey":"PROJ-BUILD-50","buildNumber":50}"""))

        val result = client.queueBuild("PROJ-BUILD", emptyMap(), stageName = "Deploy & Verify")

        assertTrue(result.isSuccess)
        val recorded = server.takeRequest()
        // Stage name with spaces + ampersand must be URL-encoded so the server sees it as a single param value
        assertTrue(
            recorded.path!!.contains("stage=Deploy+%26+Verify") ||
                recorded.path!!.contains("stage=Deploy%20%26%20Verify"),
            "Expected URL-encoded stage name in path, got ${recorded.path}"
        )
        assertTrue(recorded.path!!.contains("executeAllStages=false"))
        // Empty variables map → empty form body
        assertTrue(recorded.body.readUtf8().isEmpty())
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

    // PR 7 — write-path lessons §1: every Bamboo write must set
    // X-Atlassian-Token: no-check.

    @Test
    fun `cancelBuild sets X-Atlassian-Token header`() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))

        client.cancelBuild("PROJ-AUTO-847")

        val recorded = server.takeRequest()
        assertEquals("no-check", recorded.getHeader("X-Atlassian-Token"))
    }

    @Test
    fun `stopBuild sets X-Atlassian-Token header`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))

        client.stopBuild("PROJ-BUILD-42")

        val recorded = server.takeRequest()
        assertEquals("PUT", recorded.method)
        assertEquals("no-check", recorded.getHeader("X-Atlassian-Token"))
    }

    @Test
    fun `rerunFailedJobs uses postForm with X-Atlassian-Token`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        val result = client.rerunFailedJobs("PROJ-BUILD", 42)

        assertTrue(result.isSuccess)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("no-check", recorded.getHeader("X-Atlassian-Token"))
        // Routed via the Struts admin endpoint with planKey + buildNumber as query params.
        assertTrue(recorded.path!!.contains("/build/admin/restartBuild.action"))
        assertTrue(recorded.path!!.contains("planKey=PROJ-BUILD"))
        assertTrue(recorded.path!!.contains("buildNumber=42"))
    }

    @Test
    fun `rerunFailedJobs maps text-html response to AUTH_REDIRECT`() = runTest {
        // Atlassian login redirect: 200 with text/html means the PAT/cookie
        // expired and Bamboo swapped in the login page. postForm catches this
        // pattern and surfaces ErrorType.AUTH_REDIRECT instead of letting the
        // caller try to JSON-parse HTML.
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "text/html;charset=UTF-8")
                .setBody("<html><body>Login required</body></html>")
        )

        val result = client.rerunFailedJobs("PROJ-BUILD", 42)

        assertTrue(result is ApiResult.Error)
        assertEquals(ErrorType.AUTH_REDIRECT, (result as ApiResult.Error).type)
    }
}

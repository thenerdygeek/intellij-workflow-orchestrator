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
        val path = recorded.path ?: ""
        assertTrue(path.startsWith("/rest/api/latest/plan"), "Expected plan path; got $path")
        assertTrue(path.contains("expand=plans.plan"), "Expected expand param; got $path")
        assertTrue(path.contains("max-results=100"), "Expected max-results=100; got $path")
        assertTrue(path.contains("start-index=0"), "Expected start-index=0 (paginator always sends it); got $path")
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
    fun `getLatestResult returns build result with stages via single-key URL`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("build-result.json")))

        val result = client.getLatestResult("PROJ-BUILD")

        assertTrue(result.isSuccess)
        val build = (result as ApiResult.Success).data
        assertEquals(42, build.buildNumber)
        assertEquals("Successful", build.state)
        assertEquals(3, build.stages.stage.size)
        assertEquals("Compile", build.stages.stage[0].name)
        assertTrue(build.stages.stage[2].manual)

        val recorded = server.takeRequest()
        assertTrue(recorded.path!!.contains("/rest/api/latest/result/PROJ-BUILD/latest"))
        assertFalse(recorded.path!!.contains("/branch/"))
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
    fun `queueBuildWithStageSelection sends form-encoded POST with bamboo dot variable pairs`() = runTest {
        // Stage selection uses the REST queue endpoint with the LAST plan-order stage
        // as the ?stage= upper bound (probe-verified semantic — Bamboo runs every plan
        // stage from start through ?stage= and stops). bamboo.variable.<k>=<v> pairs
        // ride in the form body. Single request — the REST endpoint returns the
        // BambooQueueResponse directly so no follow-up lookup needed.
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"buildResultKey":"PROJ-BUILD-44","buildNumber":44,"planKey":"PROJ-BUILD"}"""
        ))

        val variables = mapOf("skipTests" to "true", "deployTarget" to "prod")
        val result = client.queueBuildWithStageSelection("PROJ-BUILD", variables, setOf("Deploy"))

        assertTrue(result.isSuccess)
        val qr = (result as ApiResult.Success).data
        assertEquals("PROJ-BUILD-44", qr.buildResultKey)
        assertEquals(44, qr.buildNumber)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        // Uses the REST queue endpoint with the LAST selected plan-order stage in the URL.
        assertTrue(
            recorded.path!!.contains("/rest/api/latest/queue/PROJ-BUILD"),
            "Expected REST queue path, got ${recorded.path}"
        )
        assertTrue(
            recorded.path!!.contains("executeAllStages=false"),
            "Expected executeAllStages=false, got ${recorded.path}"
        )
        assertTrue(
            recorded.path!!.contains("stage=Deploy"),
            "Expected stage=Deploy, got ${recorded.path}"
        )
        // Content-Type and XSRF header (set automatically by postForm).
        val contentType = recorded.getHeader("Content-Type") ?: ""
        assertTrue(
            contentType.startsWith("application/x-www-form-urlencoded"),
            "Expected application/x-www-form-urlencoded, got '$contentType'"
        )
        assertEquals("no-check", recorded.getHeader("X-Atlassian-Token"))
        // Body holds the bamboo.variable pairs (stage is in the URL, not the body).
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("bamboo.variable.skipTests=true"), "Expected skipTests in body, got: $body")
        assertTrue(body.contains("bamboo.variable.deployTarget=prod"), "Expected deployTarget in body, got: $body")
    }

    @Test
    fun `queueBuildWithStageSelection URL-encodes special characters in variable values`() = runTest {
        // dockerTagsAsJson values are JSON literals like {"svc":"1.2.3"} — braces, quotes,
        // colons all need URL-encoding. Round-trip must reach the server intact.
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"buildResultKey":"PROJ-AUTO-101","buildNumber":101}"""))

        val payload = """{"svc":"1.2.3","env":"prod"}"""
        val result = client.queueBuildWithStageSelection("PROJ-AUTO", mapOf("dockerTagsAsJson" to payload), null)

        assertTrue(result.isSuccess)
        val recorded = server.takeRequest()
        // Decoded body must contain the exact original payload + key — proves URL-encoding round-trip.
        val decoded = java.net.URLDecoder.decode(recorded.body.readUtf8(), "UTF-8")
        assertEquals("bamboo.variable.dockerTagsAsJson=$payload", decoded)
        // null selectedStages → executeAllStages=true
        assertTrue(recorded.path!!.contains("executeAllStages=true"))
        assertFalse(recorded.path!!.contains("stage="))
    }

    @Test
    fun `queueBuildWithStageSelection URL-encodes stage names with special chars in the stage query param`() = runTest {
        // Stage names with special chars (spaces, &, etc.) are URL-encoded into the
        // ?stage= query param via URLEncoder.encode(name, "UTF-8") so the round-trip
        // preserves the exact stage name on the Bamboo side.
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"buildResultKey":"PROJ-BUILD-50","buildNumber":50,"planKey":"PROJ-BUILD"}"""
        ))

        val result = client.queueBuildWithStageSelection("PROJ-BUILD", emptyMap(), setOf("Deploy & Verify"))

        assertTrue(result.isSuccess)
        val recorded = server.takeRequest()
        assertTrue(
            recorded.path!!.contains("/rest/api/latest/queue/PROJ-BUILD"),
            "Expected REST queue path, got ${recorded.path}"
        )
        // URLEncoder uses + for spaces and %26 for &.
        assertTrue(
            recorded.path!!.contains("stage=Deploy+%26+Verify") ||
                recorded.path!!.contains("stage=Deploy%20%26%20Verify"),
            "Expected URL-encoded stage name, got: ${recorded.path}"
        )
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
    fun `getRunningAndQueuedBuilds excludes terminal states and keeps all live states`() = runTest {
        // Filter by EXCLUDING terminal states (Finished/NotBuilt), not an inclusion allowlist,
        // and request a larger window so a live build can't be truncated out by finished ones.
        server.enqueue(
            MockResponse().setBody(
                """{"results":{"result":[
                    {"lifeCycleState":"Finished","buildResultKey":"PROJ-AUTO-1"},
                    {"lifeCycleState":"InProgress","buildResultKey":"PROJ-AUTO-2"},
                    {"lifeCycleState":"NotBuilt","buildResultKey":"PROJ-AUTO-3"},
                    {"lifeCycleState":"Queued","buildResultKey":"PROJ-AUTO-4"},
                    {"lifeCycleState":"Pending","buildResultKey":"PROJ-AUTO-5"}
                ]}}"""
            )
        )

        val result = client.getRunningAndQueuedBuilds("PROJ-AUTO")

        assertTrue(result.isSuccess)
        val states = (result as ApiResult.Success).data.map { it.lifeCycleState }
        assertEquals(listOf("InProgress", "Queued", "Pending"), states,
            "Finished + NotBuilt excluded; every other (live) state kept")
        val recorded = server.takeRequest()
        assertTrue(recorded.path!!.contains("max-results=25"),
            "cap must be wide enough that live builds aren't truncated out; path=${recorded.path}")
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

    @Test
    fun `enablePlanBranch sends POST to plan enable endpoint with X-Atlassian-Token`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(""))

        val result = client.enablePlanBranch("PROJ-PLAN-3")

        assertTrue(result.isSuccess)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertTrue(
            recorded.path!!.contains("/rest/api/latest/plan/PROJ-PLAN-3/enable"),
            "Expected plan enable path; got ${recorded.path}"
        )
        assertEquals("no-check", recorded.getHeader("X-Atlassian-Token"))
    }

    @Test
    fun `enablePlanBranch maps 403 to FORBIDDEN error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(403))

        val result = client.enablePlanBranch("PROJ-PLAN-3")

        assertTrue(result is ApiResult.Error)
        assertEquals(ErrorType.FORBIDDEN, (result as ApiResult.Error).type)
    }

    @Test
    fun `enablePlanBranch maps 401 to AUTH_FAILED error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        val result = client.enablePlanBranch("PROJ-PLAN-3")

        assertTrue(result is ApiResult.Error)
        assertEquals(ErrorType.AUTH_FAILED, (result as ApiResult.Error).type)
    }

    @Test
    fun `enablePlanBranch maps text-html response to AUTH_REDIRECT`() = runTest {
        // Expired PAT/session: Bamboo answers 200 with the login HTML page.
        // postForm catches the text/html pattern and surfaces AUTH_REDIRECT.
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "text/html;charset=UTF-8")
                .setBody("<html><body>Login required</body></html>")
        )

        val result = client.enablePlanBranch("PROJ-PLAN-3")

        assertTrue(result is ApiResult.Error)
        assertEquals(ErrorType.AUTH_REDIRECT, (result as ApiResult.Error).type)
    }

    // BAMBOO-COV-1: 429 rate-limit and 5xx server-error HTTP responses




    // BAMBOO-COV-2: IOException / network-failure propagation to NETWORK_ERROR

    @Test
    fun `getLatestResult returns NETWORK_ERROR after server shutdown`() = runTest {
        // Shutting down the server before the request causes a connection-refused IOException,
        // which BambooApiClient.get() catches and maps to NETWORK_ERROR.
        server.shutdown()

        val result = client.getLatestResult("PROJ-BUILD")

        assertTrue(result is ApiResult.Error, "Expected ApiResult.Error after connection refused")
        assertEquals(ErrorType.NETWORK_ERROR, (result as ApiResult.Error).type,
            "Connection-refused IOException should map to NETWORK_ERROR")
    }

    @Test
    fun `getPlans returns NETWORK_ERROR after server shutdown`() = runTest {
        server.shutdown()

        val result = client.getPlans()

        assertTrue(result is ApiResult.Error, "Expected ApiResult.Error for getPlans after shutdown")
        assertEquals(ErrorType.NETWORK_ERROR, (result as ApiResult.Error).type,
            "Connection-refused IOException should map to NETWORK_ERROR")
    }

    // BAMBOO-COV-3: non-JSON Content-Type on 2xx response → PARSE_ERROR

    @Test
    fun `getPlans returns PARSE_ERROR when response has text-html Content-Type on 200`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "text/html;charset=UTF-8")
                .setBody("<html><body>proxy page</body></html>")
        )

        val result = client.getPlans()

        assertTrue(result is ApiResult.Error, "Expected ApiResult.Error for text/html body")
        assertEquals(ErrorType.PARSE_ERROR, (result as ApiResult.Error).type,
            "text/html content-type on GET should map to PARSE_ERROR")
    }

    @Test
    fun `getLatestResult returns PARSE_ERROR when response body is malformed JSON`() = runTest {
        // A valid 200 with application/json content type but a malformed body
        // exercises the JSON parse-failure catch at BambooApiClient.get() lines 402-407.
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("not-valid-json{{{")
        )

        val result = client.getLatestResult("PROJ-BUILD")

        assertTrue(result is ApiResult.Error, "Expected ApiResult.Error for malformed JSON body")
        assertEquals(ErrorType.PARSE_ERROR, (result as ApiResult.Error).type,
            "JSON parse failure should map to PARSE_ERROR")
    }

    // BAMBOO-COV-5: stopBuild error-path tests

    @Test
    fun `stopBuild returns AUTH_FAILED on 401`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        val result = client.stopBuild("PROJ-BUILD-42")

        assertTrue(result is ApiResult.Error, "Expected ApiResult.Error for 401")
        assertEquals(ErrorType.AUTH_FAILED, (result as ApiResult.Error).type,
            "401 on stopBuild should map to AUTH_FAILED")
    }

    @Test
    fun `stopBuild returns FORBIDDEN on 403`() = runTest {
        server.enqueue(MockResponse().setResponseCode(403))

        val result = client.stopBuild("PROJ-BUILD-42")

        assertTrue(result is ApiResult.Error, "Expected ApiResult.Error for 403")
        assertEquals(ErrorType.FORBIDDEN, (result as ApiResult.Error).type,
            "403 on stopBuild should map to FORBIDDEN")
    }

    @Test
    fun `stopBuild returns NOT_FOUND on 404`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))

        val result = client.stopBuild("PROJ-BUILD-42")

        assertTrue(result is ApiResult.Error, "Expected ApiResult.Error for 404")
        assertEquals(ErrorType.NOT_FOUND, (result as ApiResult.Error).type,
            "404 on stopBuild should map to NOT_FOUND")
    }

    @Test
    fun `stopBuild maps text-html response to AUTH_REDIRECT`() = runTest {
        // Expired PAT: Bamboo answers 200 with login HTML. put() detects the
        // text/html content-type via looksLikeAuthRedirect and surfaces AUTH_REDIRECT.
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "text/html;charset=UTF-8")
                .setBody("<html><body>Login required</body></html>")
        )

        val result = client.stopBuild("PROJ-BUILD-42")

        assertTrue(result is ApiResult.Error, "Expected ApiResult.Error for auth-redirect")
        assertEquals(ErrorType.AUTH_REDIRECT, (result as ApiResult.Error).type,
            "200+text/html on stopBuild should map to AUTH_REDIRECT")
    }
}

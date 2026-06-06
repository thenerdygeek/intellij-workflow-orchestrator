package com.workflow.orchestrator.bamboo.service

import com.intellij.openapi.project.Project
import com.intellij.testFramework.LoggedErrorProcessorEnabler
import com.workflow.orchestrator.bamboo.api.BambooApiClient
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
 * [BambooServiceImpl.getLatestBuild] and [BambooServiceImpl.getPlanBranches].
 *
 * These probe the REAL service impl through the test client seam against realistic
 * Bamboo DC shapes. The monitor-unit tests already cover the model→event side;
 * these tests close the JSON→model gap.
 *
 * Monitor-relevant fields asserted:
 * - [getLatestBuild]: [BuildResultData.state], [BuildResultData.lifeCycleState],
 *   [BuildStageData.name], [BuildStageData.state],
 *   [BuildJobData.name], [BuildJobData.state], [BuildJobData.resultKey]
 * - [getPlanBranches]: [PlanBranchData.key], [PlanBranchData.name], [PlanBranchData.shortName]
 */
@ExtendWith(LoggedErrorProcessorEnabler.DoNoRethrowErrors::class)
class BambooMonitorFieldsTest {

    private val project: Project = mockk(relaxed = true)
    private lateinit var server: MockWebServer
    private lateinit var apiClient: BambooApiClient
    private lateinit var service: BambooServiceImpl

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        apiClient = BambooApiClient(
            baseUrl = server.url("/").toString().trimEnd('/'),
            tokenProvider = { "test-token" },
            connectTimeoutSeconds = 5,
            readTimeoutSeconds = 5,
        )
        service = BambooServiceImpl(project).also { it.testClientOverride = apiClient }
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    // ── getLatestBuild: state + lifeCycleState + stages[].name/state + jobs[].name/state/resultKey ─────

    @Test
    fun `getLatestBuild parses Successful Finished build with stage and job fields`() = runTest {
        // Realistic Bamboo DC /result/{key}/latest?expand=stages.stage.results.result shape.
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{
                  "key": "PROJ-PLAN-42",
                  "buildResultKey": "PROJ-PLAN-42",
                  "buildNumber": 42,
                  "state": "Successful",
                  "lifeCycleState": "Finished",
                  "buildDurationInSeconds": 325,
                  "buildRelativeTime": "5 minutes ago",
                  "stages": {
                    "stage": [
                      {
                        "name": "Compile",
                        "state": "Successful",
                        "lifeCycleState": "Finished",
                        "manual": false,
                        "buildDurationInSeconds": 90,
                        "results": {
                          "result": [
                            {
                              "key": "PROJ-PLAN-COMPILE-42",
                              "buildResultKey": "PROJ-PLAN-COMPILE-42",
                              "state": "Successful",
                              "lifeCycleState": "Finished",
                              "buildDurationInSeconds": 90,
                              "plan": {"key": "PROJ-PLAN-COMPILE", "name": "Compile", "shortName": "Compile"}
                            }
                          ]
                        }
                      },
                      {
                        "name": "Test",
                        "state": "Failed",
                        "lifeCycleState": "Finished",
                        "manual": false,
                        "buildDurationInSeconds": 235,
                        "results": {
                          "result": [
                            {
                              "key": "PROJ-PLAN-UNIT-42",
                              "buildResultKey": "PROJ-PLAN-UNIT-42",
                              "state": "Failed",
                              "lifeCycleState": "Finished",
                              "buildDurationInSeconds": 120,
                              "plan": {"key": "PROJ-PLAN-UNIT", "name": "Unit Tests", "shortName": "Unit Tests"}
                            },
                            {
                              "key": "PROJ-PLAN-INTEG-42",
                              "buildResultKey": "PROJ-PLAN-INTEG-42",
                              "state": "Successful",
                              "lifeCycleState": "Finished",
                              "buildDurationInSeconds": 115,
                              "plan": {"key": "PROJ-PLAN-INTEG", "name": "Integration Tests", "shortName": "Integration Tests"}
                            }
                          ]
                        }
                      }
                    ]
                  }
                }"""
            )
        )

        val result = service.getLatestBuild("PROJ-PLAN")

        assertFalse(result.isError, "Expected success; got: ${result.summary}")
        val build = result.data!!

        // Top-level monitor-diff fields
        assertEquals("Successful", build.state, "state must be 'Successful'")
        assertEquals("Finished", build.lifeCycleState, "lifeCycleState must be 'Finished'")
        assertEquals(42, build.buildNumber)

        // Stage-level fields
        assertEquals(2, build.stages.size)
        val compile = build.stages[0]
        assertEquals("Compile", compile.name)
        assertEquals("Successful", compile.state)

        val test = build.stages[1]
        assertEquals("Test", test.name)
        assertEquals("Failed", test.state)

        // Job-level fields (the monitor diffs job name/state/resultKey)
        assertEquals(1, compile.jobs.size)
        assertEquals("Compile", compile.jobs[0].name)
        assertEquals("Successful", compile.jobs[0].state)
        assertEquals("PROJ-PLAN-COMPILE-42", compile.jobs[0].resultKey)

        assertEquals(2, test.jobs.size)
        assertEquals("Unit Tests", test.jobs[0].name)
        assertEquals("Failed", test.jobs[0].state)
        assertEquals("PROJ-PLAN-UNIT-42", test.jobs[0].resultKey)
        assertEquals("Integration Tests", test.jobs[1].name)
        assertEquals("Successful", test.jobs[1].state)
        assertEquals("PROJ-PLAN-INTEG-42", test.jobs[1].resultKey)
    }

    @Test
    fun `getLatestBuild parses Failed InProgress build (live monitor state)`() = runTest {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{
                  "key": "PROJ-PLAN-99",
                  "buildResultKey": "PROJ-PLAN-99",
                  "buildNumber": 99,
                  "state": "Failed",
                  "lifeCycleState": "InProgress",
                  "buildDurationInSeconds": 60,
                  "stages": {
                    "stage": [
                      {
                        "name": "Build",
                        "state": "Failed",
                        "lifeCycleState": "Finished",
                        "manual": false,
                        "buildDurationInSeconds": 60,
                        "results": {
                          "result": [
                            {
                              "key": "PROJ-PLAN-BUILD-99",
                              "buildResultKey": "PROJ-PLAN-BUILD-99",
                              "state": "Failed",
                              "lifeCycleState": "Finished",
                              "buildDurationInSeconds": 60,
                              "plan": {"key": "PROJ-PLAN-BUILD", "name": "Build", "shortName": "Build"}
                            }
                          ]
                        }
                      }
                    ]
                  }
                }"""
            )
        )

        val result = service.getLatestBuild("PROJ-PLAN")

        assertFalse(result.isError)
        val build = result.data!!
        assertEquals("Failed", build.state)
        assertEquals("InProgress", build.lifeCycleState)
        assertEquals(1, build.stages.size)
        assertEquals("Build", build.stages[0].name)
        assertEquals("Failed", build.stages[0].state)
        assertEquals("PROJ-PLAN-BUILD-99", build.stages[0].jobs[0].resultKey)
    }

    @Test
    fun `getRunningBuilds maps a running build's Unknown state to its live phase`() = runTest {
        // Real DC shape (manual-captures/running-vs-stopped-build.xml, build 12): a running chain
        // build reports state="Unknown" while lifeCycleState="InProgress". The collapsed `state` must
        // surface the live phase, not the meaningless "Unknown" outcome (which made running builds
        // read as "unknown" in the agent). Note the running filter keeps it (InProgress is non-terminal).
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"results":{"result":[
                  {"key":"PROJ-REPO722-12","buildResultKey":"PROJ-REPO722-12","buildNumber":12,
                   "state":"Unknown","lifeCycleState":"InProgress","buildDurationInSeconds":0,
                   "stages":{"stage":[{"name":"Build Stage","state":"Unknown","lifeCycleState":"InProgress","manual":false,"buildDurationInSeconds":0,
                     "results":{"result":[
                       {"key":"PROJ-REPO722-SQAN-12","buildResultKey":"PROJ-REPO722-SQAN-12","state":"Unknown","lifeCycleState":"InProgress","buildDurationInSeconds":0,"plan":{"key":"PROJ-REPO722-SQAN","name":"SonarQube Analysis","shortName":"SonarQube Analysis"}},
                       {"key":"PROJ-REPO722-OSSAN-12","buildResultKey":"PROJ-REPO722-OSSAN-12","state":"Successful","lifeCycleState":"Finished","buildDurationInSeconds":36,"plan":{"key":"PROJ-REPO722-OSSAN","name":"OSS Analysis","shortName":"OSS Analysis"}}
                     ]}}]}}
                ]}}"""
            )
        )

        val result = service.getRunningBuilds("PROJ-REPO722")

        assertFalse(result.isError, result.summary)
        val builds = result.data!!
        assertEquals(1, builds.size)
        val b = builds[0]
        assertEquals("InProgress", b.state, "a running build must show its live phase, not 'Unknown'")
        assertEquals("InProgress", b.lifeCycleState)
        val jobs = b.stages[0].jobs
        assertEquals("InProgress", jobs[0].state, "the still-running job shows its live phase")
        assertEquals("Successful", jobs[1].state, "the finished job keeps its real outcome")
    }

    @Test
    fun `getLatestBuild maps a stopped NotBuilt build's Unknown state to NotBuilt`() = runTest {
        // Real DC shape (build 11): a stopped/rebuilt chain build reports state="Unknown"
        // lifeCycleState="NotBuilt". It must read as "NotBuilt" (stopped) — distinct from a running
        // build's "InProgress" and from "Successful" — so the agent can tell stopped from running.
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"key":"PROJ-REPO722-11","buildResultKey":"PROJ-REPO722-11","buildNumber":11,
                    "state":"Unknown","lifeCycleState":"NotBuilt","buildDurationInSeconds":278,
                    "stages":{"stage":[{"name":"Build Stage","state":"Unknown","lifeCycleState":"NotBuilt","manual":false,"buildDurationInSeconds":0,
                      "results":{"result":[
                        {"key":"PROJ-REPO722-BART-11","buildResultKey":"PROJ-REPO722-BART-11","state":"Unknown","lifeCycleState":"NotBuilt","buildDurationInSeconds":52,"plan":{"key":"PROJ-REPO722-BART","name":"Build Artifacts","shortName":"Build Artifacts"}}
                      ]}}]}}"""
            )
        )

        val result = service.getLatestBuild("PROJ-REPO722")

        assertFalse(result.isError, result.summary)
        val b = result.data!!
        assertEquals("NotBuilt", b.state, "a stopped/not-built build must read as NotBuilt, not 'Unknown'")
        assertEquals("NotBuilt", b.stages[0].jobs[0].state)
    }

    @Test
    fun `getLatestBuild surfaces isError and non-empty summary on 404`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))

        val result = service.getLatestBuild("PROJ-MISSING")

        assertTrue(result.isError)
        assertTrue(result.summary.isNotBlank())
    }

    @Test
    fun `getLatestBuild surfaces isError on 401 auth failure`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        val result = service.getLatestBuild("PROJ-PLAN")

        assertTrue(result.isError)
    }

    // ── getPlanBranches: key / name / shortName ────────────────────────────────

    @Test
    fun `getPlanBranches parses key name shortName for each branch`() = runTest {
        // Realistic Bamboo DC /plan/{key}/branch shape.
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{
                  "branches": {
                    "branch": [
                      {
                        "key": "PROJ-PLAN-7",
                        "name": "feature/PROJ-123-add-login",
                        "shortName": "feature/PROJ-123-add-login",
                        "enabled": true,
                        "description": ""
                      },
                      {
                        "key": "PROJ-PLAN-12",
                        "name": "bugfix/PROJ-456-null-ptr",
                        "shortName": "bugfix/PROJ-456-null-ptr",
                        "enabled": false,
                        "description": ""
                      }
                    ],
                    "size": 2,
                    "max-result": 200,
                    "start-index": 0
                  }
                }"""
            )
        )

        val result = service.getPlanBranches("PROJ-PLAN")

        assertFalse(result.isError, "Expected success; got: ${result.summary}")
        val branches = result.data!!
        assertEquals(2, branches.size)

        val first = branches[0]
        assertEquals("PROJ-PLAN-7", first.key)
        assertEquals("feature/PROJ-123-add-login", first.name)
        assertEquals("feature/PROJ-123-add-login", first.shortName)
        assertTrue(first.enabled)

        val second = branches[1]
        assertEquals("PROJ-PLAN-12", second.key)
        assertEquals("bugfix/PROJ-456-null-ptr", second.name)
        assertEquals("bugfix/PROJ-456-null-ptr", second.shortName)
        assertFalse(second.enabled)
    }

    @Test
    fun `getPlanBranches returns empty list when no branches exist`() = runTest {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"branches":{"branch":[],"size":0,"max-result":200,"start-index":0}}"""
            )
        )

        val result = service.getPlanBranches("PROJ-PLAN")

        assertFalse(result.isError)
        assertTrue(result.data!!.isEmpty())
    }

    @Test
    fun `getPlanBranches surfaces isError on 404`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))

        val result = service.getPlanBranches("PROJ-MISSING")

        assertTrue(result.isError)
    }
}

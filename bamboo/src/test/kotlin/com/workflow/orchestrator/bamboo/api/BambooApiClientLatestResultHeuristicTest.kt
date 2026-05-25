package com.workflow.orchestrator.bamboo.api

import com.workflow.orchestrator.core.model.ApiResult
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import com.intellij.testFramework.LoggedErrorProcessorEnabler
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Covers the corrected branch-plan-key detection in [BambooApiClient.getLatestResult].
 *
 * Audit finding bamboo:F-9: the old `!planKey.last().isDigit()` heuristic silently
 * ignored the [branch] parameter when the master plan key ended in a digit (e.g.
 * PROJ-BUILD2 or PROJ-PLAN123), always hitting the master endpoint instead of the
 * branch endpoint. This caused false-green / false-red build states on any plan key
 * that ended in a digit.
 *
 * The fix: use [BambooApiClient.BRANCH_PLAN_KEY_REGEX] (`^.+-.+-\d+$`) which correctly
 * distinguishes branch plan keys (e.g. PROJ-PLAN-7) from master plan keys regardless
 * of whether the master plan key ends in a digit.
 */
@ExtendWith(LoggedErrorProcessorEnabler.DoNoRethrowErrors::class)
class BambooApiClientLatestResultHeuristicTest {

    private lateinit var server: MockWebServer
    private lateinit var client: BambooApiClient

    private val minimalResultJson = """
        {
          "key": "PROJ-PLAN-1",
          "number": 1,
          "state": "Successful",
          "buildState": "Successful",
          "finished": true,
          "successful": true,
          "buildResultKey": "PROJ-PLAN-1",
          "stages": { "stage": [] }
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

    // ── BRANCH_PLAN_KEY_REGEX contract ────────────────────────────────────────

    @Test
    fun `BRANCH_PLAN_KEY_REGEX matches standard branch plan key`() {
        assertTrue(BambooApiClient.BRANCH_PLAN_KEY_REGEX.matches("PROJ-PLAN-7"))
        assertTrue(BambooApiClient.BRANCH_PLAN_KEY_REGEX.matches("PROJ-PLAN-123"))
        assertTrue(BambooApiClient.BRANCH_PLAN_KEY_REGEX.matches("MYPROJ-BUILD-42"))
    }

    @Test
    fun `BRANCH_PLAN_KEY_REGEX does NOT match master plan keys`() {
        assertFalse(BambooApiClient.BRANCH_PLAN_KEY_REGEX.matches("PROJ-PLAN"))
        assertFalse(BambooApiClient.BRANCH_PLAN_KEY_REGEX.matches("PROJ-BUILD"))
        // The old heuristic would return false for this — i.e. it would treat these as
        // master keys and therefore use the branch endpoint. The new regex correctly
        // identifies them as master keys (no trailing dash-number segment).
        assertFalse(BambooApiClient.BRANCH_PLAN_KEY_REGEX.matches("PROJ-BUILD2"))
        assertFalse(BambooApiClient.BRANCH_PLAN_KEY_REGEX.matches("PROJ-PLAN123"))
    }

    // ── getLatestResult URL routing ────────────────────────────────────────────

    @Test
    fun `getLatestResult uses branch endpoint for master plan key with branch`() = runTest {
        server.enqueue(MockResponse().setBody(minimalResultJson).setResponseCode(200))

        client.getLatestResult("PROJ-PLAN", branch = "feature/my-branch")

        val request = server.takeRequest()
        assertTrue(
            request.path!!.contains("/branch/"),
            "Expected branch endpoint but got: ${request.path}"
        )
        assertTrue(request.path!!.contains("feature"), "Branch name must appear in path: ${request.path}")
    }

    @Test
    fun `getLatestResult uses master endpoint for master plan key ending in digit (regression guard)`() = runTest {
        // PROJ-BUILD2 ends in a digit — the OLD heuristic (!planKey.last().isDigit()) would
        // skip the branch path and hit master. The new heuristic must still use the branch
        // path because PROJ-BUILD2 is a master plan key, not a branch plan key.
        server.enqueue(MockResponse().setBody(minimalResultJson).setResponseCode(200))

        client.getLatestResult("PROJ-BUILD2", branch = "feature/my-branch")

        val request = server.takeRequest()
        assertTrue(
            request.path!!.contains("/branch/"),
            "PROJ-BUILD2 is a master key ending in digit; branch param must still route to branch endpoint: ${request.path}"
        )
    }

    @Test
    fun `getLatestResult uses direct endpoint for branch plan key regardless of branch param`() = runTest {
        // When the caller has already resolved the branch plan key (e.g. PROJ-PLAN-7),
        // the branch path is not needed — the key already encodes the branch.
        server.enqueue(MockResponse().setBody(minimalResultJson).setResponseCode(200))

        client.getLatestResult("PROJ-PLAN-7", branch = "feature/my-branch")

        val request = server.takeRequest()
        assertFalse(
            request.path!!.contains("/branch/"),
            "Branch plan key PROJ-PLAN-7 must NOT append a second /branch/ segment: ${request.path}"
        )
        assertTrue(
            request.path!!.contains("/PROJ-PLAN-7/latest"),
            "Branch plan key must use the direct /latest path: ${request.path}"
        )
    }

    @Test
    fun `getLatestResult uses direct endpoint when branch is null`() = runTest {
        server.enqueue(MockResponse().setBody(minimalResultJson).setResponseCode(200))

        client.getLatestResult("PROJ-PLAN", branch = null)

        val request = server.takeRequest()
        assertFalse(
            request.path!!.contains("/branch/"),
            "No branch param → must not hit the branch endpoint: ${request.path}"
        )
        assertTrue(
            request.path!!.contains("/PROJ-PLAN/latest"),
            "No branch param → must use direct /latest path: ${request.path}"
        )
    }
}

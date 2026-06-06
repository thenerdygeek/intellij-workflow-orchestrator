package com.workflow.orchestrator.agent.tools.integration

import com.workflow.orchestrator.core.model.bamboo.BuildResultData
import com.workflow.orchestrator.core.services.BambooService
import com.workflow.orchestrator.core.services.ToolResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for the running-visibility bug-fix:
 *
 *  - `build_status`/`recent_builds` are running-aware: they compose the finished-only
 *    `getLatestBuild` with `getRunningBuilds(chainKey)` so in-flight (queued/running)
 *    builds are surfaced.
 *  - A failed running-check is NOT silently swallowed — a distinct
 *    "⚠ Could not verify in-progress/queued builds" warning is prepended.
 *
 * IMPORTANT — no master-plan-key fallback. Bamboo branch builds live ONLY under the
 * branch plan key (the resolved [chainKey], e.g. `PLAN-X523`). Callers resolve
 * `branch → chainKey` upstream (ChainKeyResolver) and the running-check queries THAT
 * key only; it never falls back to the master plan key (which would surface the wrong
 * branch's builds — see ChainKeyResolver's "no fallback to master" contract).
 *
 * All tests use [BambooBuildsTool.executeBuildStatusForTest] /
 * [BambooBuildsTool.activeBuildsOrWarning] so no IntelliJ infrastructure is needed.
 *
 * Run with: ./gradlew :agent:test --tests "*BambooBuildsToolRunningVisibility*"
 */
class BambooBuildsToolRunningVisibilityTest {

    private val tool = BambooBuildsTool()

    // ── shared test data ──────────────────────────────────────────────────────

    private fun latestFinished(planKey: String = "PLAN-X", buildNumber: Int = 2) = ToolResult(
        data = BuildResultData(
            planKey = planKey,
            buildNumber = buildNumber,
            state = "Failed",
            durationSeconds = 90L,
            buildResultKey = "$planKey-$buildNumber",
            lifeCycleState = "Finished"
        ),
        summary = "$planKey #$buildNumber: Failed",
        isError = false
    )

    private fun runningBuild(planKey: String = "PLAN-X", buildNumber: Int = 3) = ToolResult(
        data = listOf(
            BuildResultData(
                planKey = planKey,
                buildNumber = buildNumber,
                state = "Unknown",
                durationSeconds = 0L,
                buildResultKey = "$planKey-$buildNumber",
                lifeCycleState = "InProgress"
            )
        ),
        summary = "Found 1 running/queued build(s) for $planKey",
        isError = false
    )

    private fun emptyRunning(planKey: String = "PLAN-X") = ToolResult(
        data = emptyList<BuildResultData>(),
        summary = "Found 0 running/queued build(s) for $planKey",
        isError = false
    )

    private fun errorRunning(planKey: String = "PLAN-X") = ToolResult<List<BuildResultData>>(
        data = null,
        summary = "HTTP 404: $planKey not found",
        isError = true
    )

    // ═══════════════════════════════════════════════════════════════════════════
    // build_status: running build on the (resolved) chainKey is surfaced
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `build_status - running build on chainKey surfaces the IN PROGRESS notice`() = runTest {
        val service = mockk<BambooService>()
        coEvery { service.getLatestBuild("PLAN-X") } returns latestFinished()
        coEvery { service.getRunningBuilds("PLAN-X") } returns runningBuild()

        val result = tool.executeBuildStatusForTest("PLAN-X", service)

        assertFalse(result.isError)
        assertTrue(
            result.content.contains("IN PROGRESS or QUEUED"),
            "notice must mention 'IN PROGRESS or QUEUED', got:\n${result.content}"
        )
        assertTrue(
            result.content.contains("#3"),
            "notice must include the running build number #3, got:\n${result.content}"
        )
        assertTrue(
            result.content.contains("InProgress"),
            "notice must show lifecycle state 'InProgress', got:\n${result.content}"
        )
        assertTrue(
            result.content.contains("#2"),
            "latest finished build #2 must still be shown, got:\n${result.content}"
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // build_status: a branch's running build is surfaced under the BRANCH chain key —
    // and the master plan key is NEVER queried (no fallback).
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `build_status - branch running build surfaced under branch chainKey, master never queried`() = runTest {
        val service = mockk<BambooService>()
        // Caller already resolved branch -> branch chain key (e.g. PLAN-X523).
        coEvery { service.getLatestBuild("PLAN-X523") } returns latestFinished(planKey = "PLAN-X")
        coEvery { service.getRunningBuilds("PLAN-X523") } returns runningBuild(planKey = "PLAN-X523")

        val result = tool.executeBuildStatusForTest("PLAN-X523", service)

        assertTrue(
            result.content.contains("IN PROGRESS or QUEUED"),
            "branch running build must be surfaced under the branch chain key, got:\n${result.content}"
        )
        coVerify(exactly = 1) { service.getRunningBuilds("PLAN-X523") }
        // The master plan key must NEVER be queried — no fallback.
        coVerify(exactly = 0) { service.getRunningBuilds("PLAN-X") }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Failed running-check → non-silent warning (was silently emptyList)
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `build_status - running-check error produces non-silent warning, not a silent finished-only result`() = runTest {
        val service = mockk<BambooService>()
        coEvery { service.getLatestBuild("PLAN-X") } returns latestFinished()
        coEvery { service.getRunningBuilds("PLAN-X") } returns errorRunning()

        val result = tool.executeBuildStatusForTest("PLAN-X", service)

        assertFalse(result.isError, "isError should remain false (latest build still shown)")
        assertTrue(
            result.content.contains("Could not verify in-progress/queued builds"),
            "warning must mention 'Could not verify in-progress/queued builds', got:\n${result.content}"
        )
        assertTrue(
            result.content.contains("#2"),
            "latest finished build must still be shown even when running-check errors, got:\n${result.content}"
        )
        assertFalse(
            result.content.contains("IN PROGRESS or QUEUED"),
            "must not show clean running-build notice when the check itself errored, got:\n${result.content}"
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // recent_builds path: activeBuildsOrWarning surfaces the running build for the chainKey
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * We test [activeBuildsOrWarning] directly for the recent_builds path since the
     * `execute` entry-point requires IntelliJ Project infrastructure. The helper is
     * the shared core; the wiring in [BambooBuildsTool.execute] is trivially thin.
     */
    @Test
    fun `recent_builds path - activeBuildsOrWarning returns running build for chainKey`() = runTest {
        val service = mockk<BambooService>()
        coEvery { service.getRunningBuilds("PLAN-X") } returns runningBuild()

        val check = tool.activeBuildsOrWarning(service, chainKey = "PLAN-X")

        assertTrue(check.activeBuilds.isNotEmpty(), "should find the running build")
        assertFalse(check.bothErrored)
        assertTrue(
            check.activeBuilds[0].lifeCycleState == "InProgress",
            "should see InProgress lifecycle state"
        )
    }

    @Test
    fun `activeBuildsOrWarning - reports bothErrored when the chainKey query fails`() = runTest {
        val service = mockk<BambooService>()
        coEvery { service.getRunningBuilds("PLAN-X") } returns errorRunning()

        val check = tool.activeBuildsOrWarning(service, chainKey = "PLAN-X")

        assertTrue(check.activeBuilds.isEmpty())
        assertTrue(check.bothErrored, "should report bothErrored when the running-check fails")
        assertTrue(check.errorReason.isNotBlank(), "should carry an error reason")
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // No master fallback: the running-check queries ONLY the given chainKey, exactly once.
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `activeBuildsOrWarning - queries only the given chainKey exactly once (no master fallback)`() = runTest {
        val service = mockk<BambooService>()
        // Empty result on the chain key — must NOT trigger any second/master query.
        coEvery { service.getRunningBuilds("PLAN-X523") } returns emptyRunning(planKey = "PLAN-X523")

        val check = tool.activeBuildsOrWarning(service, chainKey = "PLAN-X523")

        assertTrue(check.activeBuilds.isEmpty())
        assertFalse(check.bothErrored)
        coVerify(exactly = 1) { service.getRunningBuilds(any()) }
        coVerify(exactly = 1) { service.getRunningBuilds("PLAN-X523") }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Regression: a CLEAN empty-success running-check must emit NEITHER the
    // "IN PROGRESS or QUEUED" notice NOR the "Could not verify" warning — output is
    // exactly the latest finished build. Pins that success ≠ failure for empty results.
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `build_status - clean empty running-check shows only finished build with no notice and no warning`() = runTest {
        val service = mockk<BambooService>()
        coEvery { service.getLatestBuild("PLAN-X") } returns latestFinished()
        coEvery { service.getRunningBuilds("PLAN-X") } returns emptyRunning()

        val result = tool.executeBuildStatusForTest("PLAN-X", service)

        assertFalse(result.isError)
        assertFalse(
            result.content.contains("IN PROGRESS or QUEUED"),
            "clean empty running-check must NOT emit the running-build notice, got:\n${result.content}"
        )
        assertFalse(
            result.content.contains("Could not verify"),
            "a SUCCESSFUL empty running-check must NOT emit the failure warning, got:\n${result.content}"
        )
        assertTrue(
            result.content.contains("#2"),
            "must show the latest finished build, got:\n${result.content}"
        )
    }
}

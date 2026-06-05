package com.workflow.orchestrator.agent.tools.integration

import com.workflow.orchestrator.core.model.bamboo.BuildResultData
import com.workflow.orchestrator.core.services.BambooService
import com.workflow.orchestrator.core.services.ToolResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/**
 * TDD tests for the `get_running_builds` branch-resolution fix.
 *
 * Root cause: `get_running_builds` was querying the MASTER plan key even when a
 * `branch` was supplied. Feature-branch builds live under a BRANCH plan key
 * (e.g. `PROJ-PLAN42`), not the master (`PROJ-PLAN`), so they were invisible.
 *
 * Fix mirrors the pattern used by `build_status` and `recent_builds`:
 * `ChainKeyResolver.resolveChainKey(project, planKey, branch)` is called first,
 * and the resolved branch-chain key is passed to `service.getRunningBuilds(...)`.
 *
 * Tests use [BambooBuildsTool.executeGetRunningBuildsForTest] — the internal seam
 * that accepts a pre-resolved chainKey, sidestepping IntelliJ Project
 * infrastructure (mirrors [BambooBuildsTool.executeBuildStatusForTest]).
 *
 * Run with: ./gradlew :agent:test --tests "*BambooBuildsToolGetRunningBranch*"
 */
class BambooBuildsToolGetRunningBranchTest {

    private val tool = BambooBuildsTool()

    // ── shared fixtures ───────────────────────────────────────────────────────

    private fun runningBuild(planKey: String, buildNumber: Int = 5) = ToolResult(
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

    private fun emptyRunning(planKey: String) = ToolResult(
        data = emptyList<BuildResultData>(),
        summary = "Found 0 running/queued build(s) for $planKey",
        isError = false
    )

    // ═══════════════════════════════════════════════════════════════════════════
    // CORE BUG FIX: when branch resolves to a branch-chain key, the resolved
    // key (not the master plan key) must be passed to getRunningBuilds.
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * The branch build lives under the branch chain key `PROJ-PLAN42`.
     * `executeGetRunningBuildsForTest` is called with the pre-resolved chain key,
     * which must be forwarded verbatim to `service.getRunningBuilds(...)`.
     *
     * This verifies the FIX: the handler must call getRunningBuilds with the
     * branch-chain key, NOT the master plan key.
     */
    @Test
    fun `get_running_builds with pre-resolved branch chain key calls service with that chain key`() = runTest {
        val service = mockk<BambooService>()
        // The branch chain key (e.g. PROJ-PLAN42) returns the running build.
        coEvery { service.getRunningBuilds("PROJ-PLAN42", repoName = null) } returns runningBuild("PROJ-PLAN42")

        val result = tool.executeGetRunningBuildsForTest(
            chainKey = "PROJ-PLAN42",
            repoName = null,
            service = service
        )

        assertFalse(result.isError)
        // Must have called the service with the branch-chain key.
        coVerify(exactly = 1) { service.getRunningBuilds("PROJ-PLAN42", repoName = null) }
        // Must NOT have called the service with the master plan key.
        coVerify(exactly = 0) { service.getRunningBuilds("PROJ-PLAN", repoName = any()) }
    }

    /**
     * Without a branch (null), the master plan key is passed through unchanged.
     * Regression guard: existing trunk-build queries must be unaffected.
     */
    @Test
    fun `get_running_builds without branch passes master plan key to service unchanged`() = runTest {
        val service = mockk<BambooService>()
        coEvery { service.getRunningBuilds("PROJ-PLAN", repoName = null) } returns emptyRunning("PROJ-PLAN")

        val result = tool.executeGetRunningBuildsForTest(
            chainKey = "PROJ-PLAN",   // no branch → chainKey == planKey
            repoName = null,
            service = service
        )

        assertFalse(result.isError)
        coVerify(exactly = 1) { service.getRunningBuilds("PROJ-PLAN", repoName = null) }
    }

    /**
     * `repo_name` is forwarded to the service regardless of whether a branch was
     * resolved or not (multi-repo scoping must still work after the fix).
     */
    @Test
    fun `get_running_builds forwards repo_name to service`() = runTest {
        val service = mockk<BambooService>()
        coEvery { service.getRunningBuilds("PROJ-PLAN42", repoName = "payment-service") } returns runningBuild("PROJ-PLAN42")

        tool.executeGetRunningBuildsForTest(
            chainKey = "PROJ-PLAN42",
            repoName = "payment-service",
            service = service
        )

        coVerify(exactly = 1) { service.getRunningBuilds("PROJ-PLAN42", repoName = "payment-service") }
    }

    /**
     * Snapshot of the BUG: without the fix, `get_running_builds` would call
     * `service.getRunningBuilds("PROJ-PLAN", ...)` (master key) even when a
     * feature branch was supplied. The branch build under `PROJ-PLAN42` would
     * be invisible because the master-key endpoint doesn't return branch builds.
     *
     * After the fix, the handler uses the resolved branch-chain key ("PROJ-PLAN42")
     * so `getRunningBuilds("PROJ-PLAN", ...)` is NEVER called for a branch query.
     */
    @Test
    fun `get_running_builds with branch chain key never calls service with master plan key`() = runTest {
        val service = mockk<BambooService>()
        coEvery { service.getRunningBuilds("PROJ-PLAN42", repoName = null) } returns runningBuild("PROJ-PLAN42")

        tool.executeGetRunningBuildsForTest(
            chainKey = "PROJ-PLAN42",
            repoName = null,
            service = service
        )

        // After fix: the master plan key must never be consulted for a branch query.
        coVerify(exactly = 0) { service.getRunningBuilds("PROJ-PLAN", repoName = any()) }
        coVerify(exactly = 1) { service.getRunningBuilds("PROJ-PLAN42", repoName = null) }
    }
}

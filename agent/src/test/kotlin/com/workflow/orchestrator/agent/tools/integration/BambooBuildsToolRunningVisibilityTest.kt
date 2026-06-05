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
 * Tests for the H1/H2/H6 running-visibility bug-fix:
 *
 *  H1 – [build_status] is the running-aware composite; [recent_builds] was previously
 *       finished-only. Tests here verify both actions surface in-flight builds.
 *  H2 – Branch-chain keys (e.g. PROJ-PLAN523) may 404 the running-builds endpoint.
 *       The [activeBuildsOrWarning] helper falls back to the master plan key when the
 *       chain-key call returns empty or errors.
 *  H6 – A failed running-check was previously silently swallowed (active = emptyList()).
 *       Now a distinct "⚠ Could not verify in-progress/queued builds" warning is prepended.
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
    // build_status: running build is surfaced (happy path, no fallback needed)
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `build_status - running build on chainKey surfaces the IN PROGRESS notice`() = runTest {
        val service = mockk<BambooService>()
        coEvery { service.getLatestBuild("PLAN-X") } returns latestFinished()
        coEvery { service.getRunningBuilds("PLAN-X") } returns runningBuild()

        val result = tool.executeBuildStatusForTest("PLAN-X", service, masterPlanKey = null)

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
    // H2: branch-chain key returns empty → fallback to masterPlanKey finds the build
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `build_status H2 - chainKey returns empty, masterPlanKey fallback surfaces running build`() = runTest {
        val service = mockk<BambooService>()
        coEvery { service.getLatestBuild("PLAN-X523") } returns latestFinished(planKey = "PLAN-X")
        // Branch chain key returns empty (simulates 404-then-empty from the endpoint)
        coEvery { service.getRunningBuilds("PLAN-X523") } returns emptyRunning(planKey = "PLAN-X523")
        // Master plan key returns the actual running build
        coEvery { service.getRunningBuilds("PLAN-X") } returns runningBuild(planKey = "PLAN-X")

        val result = tool.executeBuildStatusForTest(
            chainKey = "PLAN-X523",
            service = service,
            masterPlanKey = "PLAN-X"
        )

        assertFalse(result.isError)
        assertTrue(
            result.content.contains("IN PROGRESS or QUEUED"),
            "fallback must surface the running build, got:\n${result.content}"
        )
        // Verify the fallback was actually consulted.
        coVerify(exactly = 1) { service.getRunningBuilds("PLAN-X523") }
        coVerify(exactly = 1) { service.getRunningBuilds("PLAN-X") }
    }

    @Test
    fun `build_status H2 - chainKey errors, masterPlanKey fallback surfaces running build`() = runTest {
        val service = mockk<BambooService>()
        coEvery { service.getLatestBuild("PLAN-X523") } returns latestFinished(planKey = "PLAN-X")
        // Branch chain key errors (HTTP 404)
        coEvery { service.getRunningBuilds("PLAN-X523") } returns errorRunning(planKey = "PLAN-X523")
        // Master plan key returns the actual running build
        coEvery { service.getRunningBuilds("PLAN-X") } returns runningBuild(planKey = "PLAN-X")

        val result = tool.executeBuildStatusForTest(
            chainKey = "PLAN-X523",
            service = service,
            masterPlanKey = "PLAN-X"
        )

        assertFalse(result.isError)
        assertTrue(
            result.content.contains("IN PROGRESS or QUEUED"),
            "fallback must surface the running build even when chainKey 404s, got:\n${result.content}"
        )
        coVerify(exactly = 1) { service.getRunningBuilds("PLAN-X") }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // H6: BOTH attempts error → non-silent warning prepended (was silently emptyList)
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `build_status H6 - both chainKey and masterPlanKey error produces non-silent warning`() = runTest {
        val service = mockk<BambooService>()
        coEvery { service.getLatestBuild("PLAN-X523") } returns latestFinished(planKey = "PLAN-X")
        coEvery { service.getRunningBuilds("PLAN-X523") } returns errorRunning(planKey = "PLAN-X523")
        coEvery { service.getRunningBuilds("PLAN-X") } returns errorRunning(planKey = "PLAN-X")

        val result = tool.executeBuildStatusForTest(
            chainKey = "PLAN-X523",
            service = service,
            masterPlanKey = "PLAN-X"
        )

        // Must NOT be silently clean — the output must warn the LLM.
        assertFalse(result.isError, "isError should remain false (latest build still shown)")
        assertTrue(
            result.content.contains("Could not verify in-progress/queued builds"),
            "warning must mention 'Could not verify in-progress/queued builds', got:\n${result.content}"
        )
        // Latest finished build must still appear below the warning.
        assertTrue(
            result.content.contains("#2"),
            "latest finished build must still be shown even when running-check errors, got:\n${result.content}"
        )
        // Must NOT show the clean "IN PROGRESS or QUEUED" notice.
        assertFalse(
            result.content.contains("IN PROGRESS or QUEUED"),
            "must not show clean running-build notice when the check itself errored, got:\n${result.content}"
        )
    }

    @Test
    fun `build_status H6 - single key errors with no masterPlanKey produces non-silent warning`() = runTest {
        val service = mockk<BambooService>()
        coEvery { service.getLatestBuild("PLAN-X") } returns latestFinished()
        coEvery { service.getRunningBuilds("PLAN-X") } returns errorRunning()

        val result = tool.executeBuildStatusForTest(
            chainKey = "PLAN-X",
            service = service,
            masterPlanKey = null
        )

        assertFalse(result.isError)
        assertTrue(
            result.content.contains("Could not verify in-progress/queued builds"),
            "single-key error must also produce the non-silent warning, got:\n${result.content}"
        )
        assertTrue(result.content.contains("#2"), "latest finished build must still appear")
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // recent_builds H1: running-aware — notice is prepended when active builds exist
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

        val check = tool.activeBuildsOrWarning(service, chainKey = "PLAN-X", masterPlanKey = null)

        assertTrue(check.activeBuilds.isNotEmpty(), "should find the running build")
        assertFalse(check.bothErrored)
        assertTrue(
            check.activeBuilds[0].lifeCycleState == "InProgress",
            "should see InProgress lifecycle state"
        )
    }

    @Test
    fun `recent_builds path - activeBuildsOrWarning uses masterPlanKey fallback when chainKey returns empty`() = runTest {
        val service = mockk<BambooService>()
        coEvery { service.getRunningBuilds("PLAN-X523") } returns emptyRunning(planKey = "PLAN-X523")
        coEvery { service.getRunningBuilds("PLAN-X") } returns runningBuild(planKey = "PLAN-X")

        val check = tool.activeBuildsOrWarning(service, chainKey = "PLAN-X523", masterPlanKey = "PLAN-X")

        assertTrue(check.activeBuilds.isNotEmpty(), "fallback should find the running build")
        assertFalse(check.bothErrored)
        coVerify(exactly = 1) { service.getRunningBuilds("PLAN-X") }
    }

    @Test
    fun `recent_builds path - activeBuildsOrWarning both-errored when no fallback available`() = runTest {
        val service = mockk<BambooService>()
        coEvery { service.getRunningBuilds("PLAN-X") } returns errorRunning()

        val check = tool.activeBuildsOrWarning(service, chainKey = "PLAN-X", masterPlanKey = null)

        assertTrue(check.activeBuilds.isEmpty())
        assertTrue(check.bothErrored, "should report bothErrored when only attempt fails")
        assertTrue(check.errorReason.isNotBlank(), "should carry an error reason")
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // activeBuildsOrWarning: no fallback attempted when masterPlanKey == chainKey
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `activeBuildsOrWarning - no secondary call when masterPlanKey equals chainKey`() = runTest {
        val service = mockk<BambooService>()
        coEvery { service.getRunningBuilds("PLAN-X") } returns emptyRunning()

        // masterPlanKey == chainKey → should not trigger a second call
        val check = tool.activeBuildsOrWarning(service, chainKey = "PLAN-X", masterPlanKey = "PLAN-X")

        assertTrue(check.activeBuilds.isEmpty())
        assertFalse(check.bothErrored)
        coVerify(exactly = 1) { service.getRunningBuilds(any()) }
    }

    @Test
    fun `activeBuildsOrWarning - no secondary call when masterPlanKey is null`() = runTest {
        val service = mockk<BambooService>()
        coEvery { service.getRunningBuilds("PLAN-X") } returns emptyRunning()

        val check = tool.activeBuildsOrWarning(service, chainKey = "PLAN-X", masterPlanKey = null)

        assertTrue(check.activeBuilds.isEmpty())
        assertFalse(check.bothErrored)
        coVerify(exactly = 1) { service.getRunningBuilds(any()) }
    }
}

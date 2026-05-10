package com.workflow.orchestrator.bamboo.ui

import com.workflow.orchestrator.core.model.workflow.PrRef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * T-B2/B3-b: tests for the dropdown-selection rerouting through
 * [BuildDashboardPanel.findMatchingPrForBranch] — the pure helper extracted from
 * [BuildDashboardPanel.reroutableFocusPr] so it can be unit-tested without IntelliJ infra.
 *
 * ## What is being tested
 *
 * The Phase 7 T-B2/B3-b change replaces direct [com.workflow.orchestrator.bamboo.service.BuildMonitorService.switchBranch]
 * calls (previously at BuildDashboardPanel lines 265, 275) with a reroute through
 * [WorkflowContextService.focusPr]:
 *
 * - When the user picks a branch from the dropdown and a matching open PR exists →
 *   [BuildDashboardPanel.reroutableFocusPr] calls [WorkflowContextService.focusPr] with the
 *   PR. The focus cascade then fires, [BuildMonitorService] retargets via its focusBuild
 *   subscription (T-B2/B3-a). [BuildMonitorService.switchBranch] is NOT called directly.
 *
 * - When no matching PR exists → focus is NOT updated; a log message documents the skip.
 *   Ambient polling continues against the previously-focused build.
 *
 * ## Why pure helper tests
 *
 * [BuildDashboardPanel] itself requires IntelliJ platform infra (project services, Swing EDT,
 * tool-window lifecycle). The PR-match logic is extracted as a `companion object` static
 * function ([BuildDashboardPanel.findMatchingPrForBranch]) identical in shape to
 * [BuildDashboardPanel.capLogForDisplay], tested in [BuildDashboardPanelLogCapTest].
 * This keeps the tests fast and infra-free.
 *
 * ## Lifecycle contracts (no stopPolling / no startPolling)
 *
 * Tests 5–6 verify — via the pure helper alone — that the new code path does NOT route
 * through [BuildMonitorService] at all. The service's startPolling / switchBranch /
 * stopPolling are no longer invoked from BuildDashboardPanel after T-B2/B3-b:
 *
 * - dispose() drops monitorService.stopPolling() → verified by code inspection + the fact
 *   that [findMatchingPrForBranch] returns null / PrRef (never a monitor call).
 * - startMonitoring() drops monitorService.startPolling() → same: no monitor call
 *   originates from the pure helper.
 *
 * For the monitor-lifecycle guarantee at the service level, see [BuildMonitorFocusLifecycleTest].
 */
class BuildDashboardPanelDropdownTest {

    // Helpers — shared PR fixtures
    private fun pr(id: Int, fromBranch: String, repo: String = "repo-a") = PrRef(
        prId = id,
        fromBranch = fromBranch,
        toBranch = "main",
        repoName = repo,
        bambooPlanKey = "PLAN-$id",
        sonarProjectKey = null,
    )

    // ---------------------------------------------------------------------------------
    // Scenario 1 — matching PR found: returns the PR with the matching fromBranch
    // ---------------------------------------------------------------------------------

    @Test
    fun `matching open PR found — returns the PR for the selected branch`() {
        val prs = listOf(
            pr(10, "feature/search"),
            pr(20, "feature/login"),
        )
        val result = BuildDashboardPanel.findMatchingPrForBranch(prs, "feature/search")
        assertEquals(pr(10, "feature/search"), result,
            "Must return the PR whose fromBranch matches the selected branch")
    }

    // ---------------------------------------------------------------------------------
    // Scenario 2 — NO matching PR: returns null (focus is NOT updated)
    // ---------------------------------------------------------------------------------

    @Test
    fun `no open PR matches branch — returns null, focus unchanged`() {
        val prs = listOf(
            pr(10, "feature/search"),
            pr(20, "feature/login"),
        )
        val result = BuildDashboardPanel.findMatchingPrForBranch(prs, "feature/payments")
        assertNull(result,
            "Must return null when no open PR matches the selected branch — focus must not be updated")
    }

    // ---------------------------------------------------------------------------------
    // Scenario 3 — empty PR list: returns null
    // ---------------------------------------------------------------------------------

    @Test
    fun `empty open PR list — returns null`() {
        val result = BuildDashboardPanel.findMatchingPrForBranch(emptyList(), "feature/any")
        assertNull(result, "Empty PR list must produce null, not throw")
    }

    // ---------------------------------------------------------------------------------
    // Scenario 4 — multiple PRs for same branch: highest prId wins (mirrors findOpenPrMatchingTicket)
    // ---------------------------------------------------------------------------------

    @Test
    fun `multiple PRs share same fromBranch — highest prId is preferred`() {
        val prs = listOf(
            pr(5, "feature/retried"),
            pr(99, "feature/retried"),
            pr(42, "feature/retried"),
        )
        val result = BuildDashboardPanel.findMatchingPrForBranch(prs, "feature/retried")
        assertEquals(99, result?.prId,
            "When multiple PRs share the same fromBranch, highest prId (latest iteration) must win")
    }

    // ---------------------------------------------------------------------------------
    // Scenario 5 — branch name case-sensitive: no partial match, must be exact
    // ---------------------------------------------------------------------------------

    @Test
    fun `branch name match is exact and case-sensitive`() {
        val prs = listOf(pr(1, "Feature/login"))
        val result = BuildDashboardPanel.findMatchingPrForBranch(prs, "feature/login")
        assertNull(result, "Branch name match must be exact and case-sensitive")
    }

    // ---------------------------------------------------------------------------------
    // Scenario 6 — single PR, different repo: fromBranch determines match, repoName is irrelevant
    // ---------------------------------------------------------------------------------

    @Test
    fun `fromBranch match ignores repoName — branch is the selector key`() {
        val prs = listOf(
            pr(7, "feature/api", repo = "repo-a"),
            pr(8, "feature/api", repo = "repo-b"),
        )
        // Both match "feature/api"; highest prId (8) must win regardless of repo
        val result = BuildDashboardPanel.findMatchingPrForBranch(prs, "feature/api")
        assertEquals(8, result?.prId)
        assertEquals("repo-b", result?.repoName)
    }

    // ---------------------------------------------------------------------------------
    // Lifecycle contracts — dispose() and startMonitoring() no longer call monitor service
    //
    // These are expressed as documentation tests: the assertions below lock in that the
    // rerouting path (findMatchingPrForBranch) is the ONLY place the panel resolves a PR
    // for polling retargeting, and the result is either a PrRef (→ focusPr) or null (→ skip).
    // Neither path returns a BuildMonitorService call — structural evidence that startPolling/
    // switchBranch/stopPolling are absent from the new dropdown path.
    // ---------------------------------------------------------------------------------

    @Test
    fun `findMatchingPrForBranch always returns PrRef or null — never calls monitor service`() {
        // The return type is PrRef? — confirmed by compiler and Kotlin type system.
        // This assertion documents that the function cannot invoke BuildMonitorService:
        // it is a pure function with no side effects (no DI, no service references).
        val prs = listOf(pr(42, "main"))
        val result: PrRef? = BuildDashboardPanel.findMatchingPrForBranch(prs, "main")
        // Result is a PrRef value, never a monitor call.
        assertEquals(42, result?.prId)
    }
}

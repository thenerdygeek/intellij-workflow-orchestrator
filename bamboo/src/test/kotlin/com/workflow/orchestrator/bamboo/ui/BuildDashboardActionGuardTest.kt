package com.workflow.orchestrator.bamboo.ui

import com.workflow.orchestrator.core.model.workflow.PrRef
import com.workflow.orchestrator.core.model.workflow.RepoRef
import com.workflow.orchestrator.core.model.workflow.WorkflowContext
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Item 10 — verifies the Build-tab branch-match guard.
 *
 * When [WorkflowContext.interactionMode] == ReadOnly the action toolbar gates writes
 * (Refresh, Trigger Build, Trigger Manual Stage) — display rows stay visible. The
 * decision lives in [BuildDashboardActionGate] which is a pure object, so this test
 * doesn't need to instantiate the heavyweight `BuildDashboardPanel`.
 *
 * After the 2026-04-29 editor-coupling rip-out, the guard reads `prRepoBranch` (the
 * focused PR's repo's actual checked-out branch, populated by `WorkflowContextService`
 * from `GitRepositoryManager`) — never `activeBranch` / `activeRepo` (editor-derived).
 */
class BuildDashboardActionGuardTest {

    private fun pr(from: String, inRepo: String = "repo") = PrRef(
        prId = 99,
        fromBranch = from,
        toBranch = "main",
        repoName = inRepo,
        bambooPlanKey = null,
        sonarProjectKey = null,
    )

    private fun repo(name: String) = RepoRef(name, "P", name, "/p/$name")

    @Test
    fun `Live when no PR focused — actions enabled`() {
        val ctx = WorkflowContext(focusPr = null)
        assertTrue(BuildDashboardActionGate.isLiveMode(ctx))
    }

    @Test
    fun `Live when focused PR fromBranch matches prRepoBranch — actions enabled`() {
        val ctx = WorkflowContext(focusPr = pr("feat/abc"), prRepoBranch = "feat/abc")
        assertTrue(BuildDashboardActionGate.isLiveMode(ctx))
    }

    @Test
    fun `ReadOnly when prRepoBranch differs from focused PR fromBranch — actions disabled`() {
        val ctx = WorkflowContext(focusPr = pr("feat/abc"), prRepoBranch = "main")
        assertFalse(BuildDashboardActionGate.isLiveMode(ctx))
    }

    @Test
    fun `ReadOnly when prRepoBranch is null with focused PR — actions disabled`() {
        val ctx = WorkflowContext(focusPr = pr("feat/abc"), prRepoBranch = null)
        assertFalse(BuildDashboardActionGate.isLiveMode(ctx))
    }

    /**
     * Editor-coupling regression guard. Previously the gate also consulted
     * `activeBranch` / `activeRepo` — opening any random file in the wrong submodule
     * would silently flip to ReadOnly even when the PR's own repo had the right
     * branch checked out. Now the editor slice is irrelevant.
     */
    @Test
    fun `Live when prRepoBranch matches PR branch even when editor sits in unrelated repo`() {
        val ctx = WorkflowContext(
            focusPr = pr("feat/abc", inRepo = "repo-a"),
            prRepoBranch = "feat/abc",
            // Editor in a totally different submodule on a totally different branch.
            activeRepo = repo("repo-b"),
            activeBranch = "main",
        )
        assertTrue(BuildDashboardActionGate.isLiveMode(ctx))
    }

    @Test
    fun `tooltip names the focused PR's source branch when ReadOnly`() {
        val ctx = WorkflowContext(focusPr = pr("feat/xyz"), prRepoBranch = "main")
        val tip = BuildDashboardActionGate.readOnlyTooltip(ctx)
        assertTrue(tip.contains("feat/xyz"), "Tooltip should name the source branch, was: $tip")
        assertTrue(tip.startsWith("Disabled:"), "Tooltip should start with Disabled, was: $tip")
    }
}

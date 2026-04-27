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
        val ctx = WorkflowContext(focusPr = null, activeBranch = "feat/abc")
        assertTrue(BuildDashboardActionGate.isLiveMode(ctx))
    }

    @Test
    fun `Live when focused PR fromBranch matches activeBranch and repo matches — actions enabled`() {
        val ctx = WorkflowContext(
            focusPr = pr("feat/abc"),
            activeBranch = "feat/abc",
            activeRepo = repo("repo"),
        )
        assertTrue(BuildDashboardActionGate.isLiveMode(ctx))
    }

    @Test
    fun `ReadOnly when activeBranch differs from focused PR fromBranch — actions disabled`() {
        val ctx = WorkflowContext(
            focusPr = pr("feat/abc"),
            activeBranch = "main",
            activeRepo = repo("repo"),
        )
        assertFalse(BuildDashboardActionGate.isLiveMode(ctx))
    }

    @Test
    fun `ReadOnly when activeBranch is null with focused PR — actions disabled`() {
        val ctx = WorkflowContext(focusPr = pr("feat/abc"), activeBranch = null)
        assertFalse(BuildDashboardActionGate.isLiveMode(ctx))
    }

    /**
     * H1 from the 2026-04-27 sweep code review: actions must NOT enable when the editor's
     * repo differs from the focused PR's repo, even when both share the same branch name.
     * Common in multi-module setups where two submodules share a `feature/ABC-123-foo`
     * branch from the same Jira ticket — without this check, Trigger Build would target
     * the wrong submodule's plan.
     */
    @Test
    fun `ReadOnly when branch matches but PR repo differs from active repo — actions disabled`() {
        val ctx = WorkflowContext(
            focusPr = pr("feat/abc", inRepo = "repo-a"),
            activeBranch = "feat/abc",
            activeRepo = repo("repo-b"),
        )
        assertFalse(BuildDashboardActionGate.isLiveMode(ctx))
    }

    @Test
    fun `tooltip names the focused PR's source branch when ReadOnly`() {
        val ctx = WorkflowContext(focusPr = pr("feat/xyz"), activeBranch = "main")
        val tip = BuildDashboardActionGate.readOnlyTooltip(ctx)
        assertTrue(tip.contains("feat/xyz"), "Tooltip should name the source branch, was: $tip")
        assertTrue(tip.startsWith("Disabled:"), "Tooltip should start with Disabled, was: $tip")
    }
}

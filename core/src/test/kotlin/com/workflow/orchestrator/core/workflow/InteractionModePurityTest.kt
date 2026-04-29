package com.workflow.orchestrator.core.workflow

import com.workflow.orchestrator.core.model.workflow.InteractionMode
import com.workflow.orchestrator.core.model.workflow.PrRef
import com.workflow.orchestrator.core.model.workflow.RepoRef
import com.workflow.orchestrator.core.model.workflow.WorkflowContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Pins the `WorkflowContext.interactionMode` derived getter to its declared inputs.
 *
 * After the 2026-04-29 editor-coupling rip-out, `interactionMode` reads the focused PR's
 * OWN repo branch (`prRepoBranch`, populated by `WorkflowContextService` from
 * `GitRepositoryManager`) — NOT the editor-derived `activeBranch` / `activeRepo`. The
 * editor's open file is irrelevant: opening any random file in any submodule must not
 * affect the Live/ReadOnly decision.
 */
class InteractionModePurityTest {
    private fun repo(name: String) = RepoRef(name, "P", name, "/p/$name")

    @Test fun `Live when focusPr is null`() {
        assertEquals(InteractionMode.Live, WorkflowContext().interactionMode)
    }

    @Test fun `Live when focusPr fromBranch matches prRepoBranch`() {
        val pr = PrRef(42, "feat/abc", "main", "repo", null, null)
        assertEquals(
            InteractionMode.Live,
            WorkflowContext(focusPr = pr, prRepoBranch = "feat/abc").interactionMode
        )
    }

    @Test fun `ReadOnly when focusPr fromBranch differs from prRepoBranch`() {
        val pr = PrRef(42, "bugfix/xyz", "main", "repo", null, null)
        assertEquals(
            InteractionMode.ReadOnly,
            WorkflowContext(focusPr = pr, prRepoBranch = "feat/abc").interactionMode
        )
    }

    @Test fun `ReadOnly when prRepoBranch is null but focusPr exists`() {
        val pr = PrRef(42, "bugfix/xyz", "main", "repo", null, null)
        assertEquals(InteractionMode.ReadOnly, WorkflowContext(focusPr = pr).interactionMode)
    }

    /**
     * Editor-coupling regression guard. The user could open a `.txt` file in a different
     * submodule while the PR's own repo is on the right branch — the banner must NOT
     * appear in that case. activeBranch / activeRepo / editorModule must never sway the
     * decision.
     */
    @Test fun `Live when prRepoBranch matches PR branch even with mismatched editor state`() {
        val pr = PrRef(42, "feat/abc", "main", "repo-a", null, null)
        val ctx = WorkflowContext(
            focusPr = pr,
            prRepoBranch = "feat/abc",
            // Editor sits in a totally different repo on a totally different branch.
            activeRepo = repo("repo-b"),
            activeBranch = "main",
        )
        assertEquals(InteractionMode.Live, ctx.interactionMode)
    }

    /**
     * Multi-module: two submodules can share a branch name (`feature/ABC-123-foo`).
     * `prRepoBranch` is the PR's repo's actual branch as read by the service; the data
     * class never tries to disambiguate by repo name itself — the service did the
     * lookup against the right `GitRepository` and wrote `prRepoBranch` accordingly.
     */
    @Test fun `repo identity is the service's responsibility — data class trusts prRepoBranch verbatim`() {
        val pr = PrRef(42, "feat/abc", "main", "repo-a", null, null)
        // Service looked up repo-a's branch and wrote it. Editor sits in repo-b also on
        // feat/abc, but that's irrelevant — getter only consults prRepoBranch.
        val ctx = WorkflowContext(
            focusPr = pr,
            prRepoBranch = "feat/abc",
            activeRepo = repo("repo-b"),
            activeBranch = "feat/abc",
        )
        assertEquals(InteractionMode.Live, ctx.interactionMode)
    }

    @Test fun `interactionMode result is stable across 100 invocations with no state change`() {
        val pr = PrRef(42, "feat/abc", "main", "repo", null, null)
        val ctx = WorkflowContext(focusPr = pr, prRepoBranch = "feat/abc")
        repeat(100) { assertEquals(InteractionMode.Live, ctx.interactionMode) }
    }

    /**
     * Purity check: `interactionMode` is determined SOLELY by `focusPr` + `prRepoBranch`.
     * If a future maintainer adds a contributing factor (or re-introduces the editor
     * coupling), this test fails because varying the other declared fields here does not
     * change interactionMode.
     */
    @Test fun `interactionMode depends only on focusPr and prRepoBranch`() {
        val pr = PrRef(42, "feat/abc", "main", "repo", null, null)
        val baselineLive = WorkflowContext(focusPr = pr, prRepoBranch = "feat/abc").interactionMode
        val baselineReadOnly = WorkflowContext(focusPr = pr, prRepoBranch = "main").interactionMode
        assertEquals(InteractionMode.Live, baselineLive)
        assertEquals(InteractionMode.ReadOnly, baselineReadOnly)

        val ctxLiveBase = WorkflowContext(focusPr = pr, prRepoBranch = "feat/abc")
        val ctxLiveWithExtras = ctxLiveBase.copy(
            activeTicket = com.workflow.orchestrator.core.model.workflow.TicketRef("X-1", "s"),
            activeRepo = repo("repo-b"),
            activeBranch = "totally-different-branch",
            editorModule = com.workflow.orchestrator.core.model.workflow.ModuleRef("m", "/p"),
            projectModules = listOf(com.workflow.orchestrator.core.model.workflow.ModuleRef("m", "/p")),
            focusBuild = com.workflow.orchestrator.core.model.workflow.BuildRef("PLAN", 1, "feat/abc", null),
            focusQualityScope = com.workflow.orchestrator.core.model.workflow.QualityScope("k", "feat/abc", null),
        )
        assertEquals(ctxLiveBase.interactionMode, ctxLiveWithExtras.interactionMode,
            "interactionMode changed when an unrelated declared field changed — invariant broken")
    }
}

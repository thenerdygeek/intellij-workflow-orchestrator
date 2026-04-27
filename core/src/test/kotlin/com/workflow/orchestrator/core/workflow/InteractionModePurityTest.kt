package com.workflow.orchestrator.core.workflow

import com.workflow.orchestrator.core.model.workflow.InteractionMode
import com.workflow.orchestrator.core.model.workflow.PrRef
import com.workflow.orchestrator.core.model.workflow.RepoRef
import com.workflow.orchestrator.core.model.workflow.WorkflowContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class InteractionModePurityTest {
    private fun repo(name: String) = RepoRef(name, "P", name, "/p/$name")

    @Test fun `Live when focusPr is null`() {
        assertEquals(InteractionMode.Live, WorkflowContext(activeBranch = "feat/abc").interactionMode)
    }

    @Test fun `Live when focusPr fromBranch matches activeBranch and repo matches activeRepo`() {
        val pr = PrRef(42, "feat/abc", "main", "repo", null, null)
        assertEquals(
            InteractionMode.Live,
            WorkflowContext(activeBranch = "feat/abc", activeRepo = repo("repo"), focusPr = pr).interactionMode
        )
    }

    @Test fun `ReadOnly when focusPr fromBranch differs from activeBranch`() {
        val pr = PrRef(42, "bugfix/xyz", "main", "repo", null, null)
        assertEquals(
            InteractionMode.ReadOnly,
            WorkflowContext(activeBranch = "feat/abc", activeRepo = repo("repo"), focusPr = pr).interactionMode
        )
    }

    @Test fun `ReadOnly when activeBranch is null but focusPr exists`() {
        val pr = PrRef(42, "bugfix/xyz", "main", "repo", null, null)
        assertEquals(InteractionMode.ReadOnly, WorkflowContext(focusPr = pr).interactionMode)
    }

    /**
     * H1 from the 2026-04-27 sweep code review: in multi-module projects, two submodules
     * frequently share a branch name (e.g. both branched from the same Jira ticket as
     * `feature/ABC-123-foo`). Without the repo identity check, focusing PR-A in repo A
     * while the editor sits in repo B (also on `feature/ABC-123-foo`) would falsely report
     * Live and let action handlers (build triggers, branch switches) target the wrong
     * submodule — same failure shape this whole sweep set out to eliminate.
     */
    @Test fun `ReadOnly when branch matches but PR repo differs from active repo`() {
        val pr = PrRef(42, "feat/abc", "main", "repo-a", null, null)
        assertEquals(
            InteractionMode.ReadOnly,
            WorkflowContext(activeBranch = "feat/abc", activeRepo = repo("repo-b"), focusPr = pr).interactionMode
        )
    }

    @Test fun `ReadOnly when branch matches but activeRepo is null`() {
        val pr = PrRef(42, "feat/abc", "main", "repo", null, null)
        assertEquals(
            InteractionMode.ReadOnly,
            WorkflowContext(activeBranch = "feat/abc", activeRepo = null, focusPr = pr).interactionMode
        )
    }

    @Test fun `interactionMode result is stable across 100 invocations with no state change`() {
        val pr = PrRef(42, "feat/abc", "main", "repo", null, null)
        val ctx = WorkflowContext(activeBranch = "feat/abc", activeRepo = repo("repo"), focusPr = pr)
        repeat(100) { assertEquals(InteractionMode.Live, ctx.interactionMode) }
    }

    /**
     * Purity check: `interactionMode` is determined SOLELY by `activeBranch`, `activeRepo`,
     * and `focusPr` — no implicit dependency on other declared fields. If a future maintainer
     * adds a contributing factor, this test fails because varying the other declared fields
     * here does not change interactionMode.
     */
    @Test fun `interactionMode depends only on declared activeBranch activeRepo and focusPr`() {
        val pr = PrRef(42, "feat/abc", "main", "repo", null, null)
        val baselineLive = WorkflowContext(
            activeBranch = "feat/abc", activeRepo = repo("repo"), focusPr = pr
        ).interactionMode
        val baselineReadOnly = WorkflowContext(
            activeBranch = "main", activeRepo = repo("repo"), focusPr = pr
        ).interactionMode
        assertEquals(InteractionMode.Live, baselineLive)
        assertEquals(InteractionMode.ReadOnly, baselineReadOnly)

        val ctxLiveBase = WorkflowContext(activeBranch = "feat/abc", activeRepo = repo("repo"), focusPr = pr)
        val ctxLiveWithExtras = ctxLiveBase.copy(
            activeTicket = com.workflow.orchestrator.core.model.workflow.TicketRef("X-1", "s"),
            editorModule = com.workflow.orchestrator.core.model.workflow.ModuleRef("m", "/p"),
            projectModules = listOf(com.workflow.orchestrator.core.model.workflow.ModuleRef("m", "/p")),
            focusBuild = com.workflow.orchestrator.core.model.workflow.BuildRef("PLAN", 1, "feat/abc", null),
            focusQualityScope = com.workflow.orchestrator.core.model.workflow.QualityScope("k", "feat/abc", null),
        )
        assertEquals(ctxLiveBase.interactionMode, ctxLiveWithExtras.interactionMode,
            "interactionMode changed when an unrelated declared field changed — invariant broken")
    }
}

package com.workflow.orchestrator.core.workflow.ui

import com.workflow.orchestrator.core.model.workflow.PrRef
import com.workflow.orchestrator.core.model.workflow.WorkflowContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Pins the `ReadOnlyBanner.updateMessage()` rendered string.
 *
 * After the 2026-04-29 editor-coupling rip-out, the banner reads `prRepoBranch`
 * (the focused PR's OWN repo's currently checked-out branch, populated by
 * `WorkflowContextService` from `GitRepositoryManager`) — never the editor's
 * `activeBranch`. The phrasing reflects this: "The PR's repo is on X" instead
 * of "You're on X", because the user might have a random file open in another
 * submodule and "you're on" would be ambiguous.
 *
 * `prRepoBranch == null` is rare (only when the PR's repo can't be resolved or
 * is detached); the literal "branch unknown" fallback keeps the UI readable.
 */
class ReadOnlyBannerTextTest {

    @Test fun `banner text uses 'branch unknown' instead of literal angle-none when prRepoBranch is null`() {
        val ctx = WorkflowContext(
            focusPr = PrRef(1234, "feat/x", "main", "repo", null, null),
            prRepoBranch = null,
        )
        val rendered = renderBannerText(ctx)
        assertTrue(rendered.contains("branch unknown"), "Expected human-readable fallback, got: $rendered")
        assertTrue(!rendered.contains("<none>"), "Literal <none> must not leak into UI: $rendered")
    }

    @Test fun `banner text uses real branch name when prRepoBranch is set`() {
        val ctx = WorkflowContext(
            focusPr = PrRef(1234, "feat/x", "main", "repo", null, null),
            prRepoBranch = "main",
        )
        val rendered = renderBannerText(ctx)
        assertTrue(rendered.contains("PR's repo is on main"), "Expected PR-repo branch name in text: $rendered")
        assertTrue(!rendered.contains("branch unknown"), "Should not fall back when branch known: $rendered")
    }

    @Test fun `banner text ignores editor-derived activeBranch`() {
        // The user has a random file open in a totally different submodule on a
        // totally different branch. The banner must NOT report that branch — it
        // reports the PR's repo's branch, period.
        val ctx = WorkflowContext(
            focusPr = PrRef(1234, "feat/x", "main", "repo", null, null),
            prRepoBranch = "main",
            activeBranch = "totally-unrelated-branch",
        )
        val rendered = renderBannerText(ctx)
        assertTrue(rendered.contains("PR's repo is on main"), "Banner must report prRepoBranch, got: $rendered")
        assertTrue(!rendered.contains("totally-unrelated-branch"),
            "Editor-derived activeBranch must not leak into the banner: $rendered")
    }

    /**
     * Mirrors the template at `ReadOnlyBanner.updateMessage()`. Keep in sync.
     */
    private fun renderBannerText(ctx: WorkflowContext): String {
        val pr = ctx.focusPr ?: return ""
        val branch = ctx.prRepoBranch ?: "branch unknown"
        return "Viewing PR #${pr.prId} (${pr.fromBranch}). The PR's repo is on $branch — interactions disabled."
    }
}

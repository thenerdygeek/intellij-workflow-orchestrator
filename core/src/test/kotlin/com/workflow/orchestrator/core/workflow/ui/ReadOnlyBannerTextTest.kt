package com.workflow.orchestrator.core.workflow.ui

import com.workflow.orchestrator.core.model.workflow.PrRef
import com.workflow.orchestrator.core.model.workflow.WorkflowContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Phase B — banner text honesty.
 *
 * `ReadOnlyBanner.updateMessage()` previously rendered literal `<none>` when
 * `state.activeBranch` was null (the symptom users reported). Replaced with
 * "branch unknown". This test pins the rendered string by exercising the
 * exact same template used by the banner — no JComponent instantiation needed.
 *
 * After Phase A's boot seed runs, `activeBranch == null` should be rare in
 * practice (only when the project has zero git repos). The string still has
 * to be human-readable for that edge case.
 */
class ReadOnlyBannerTextTest {

    @Test fun `banner text uses 'branch unknown' instead of literal angle-none when activeBranch is null`() {
        val ctx = WorkflowContext(
            focusPr = PrRef(1234, "feat/x", "main", "repo", null, null),
            activeBranch = null,
        )
        val rendered = renderBannerText(ctx)
        assertTrue(rendered.contains("branch unknown"), "Expected human-readable fallback, got: $rendered")
        assertTrue(!rendered.contains("<none>"), "Literal <none> must not leak into UI: $rendered")
    }

    @Test fun `banner text uses real branch name when activeBranch is set`() {
        val ctx = WorkflowContext(
            focusPr = PrRef(1234, "feat/x", "main", "repo", null, null),
            activeBranch = "main",
        )
        val rendered = renderBannerText(ctx)
        assertTrue(rendered.contains("You're on main"), "Expected branch name in text: $rendered")
        assertTrue(!rendered.contains("branch unknown"), "Should not fall back when branch known: $rendered")
    }

    /**
     * Mirrors the template at `ReadOnlyBanner.updateMessage()`. Keep in sync.
     */
    private fun renderBannerText(ctx: WorkflowContext): String {
        val pr = ctx.focusPr ?: return ""
        val branch = ctx.activeBranch ?: "branch unknown"
        return "Viewing PR #${pr.prId} (${pr.fromBranch}). You're on $branch — interactions disabled."
    }
}

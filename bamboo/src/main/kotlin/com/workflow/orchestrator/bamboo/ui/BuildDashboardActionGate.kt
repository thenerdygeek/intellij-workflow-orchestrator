package com.workflow.orchestrator.bamboo.ui

import com.workflow.orchestrator.core.model.workflow.InteractionMode
import com.workflow.orchestrator.core.model.workflow.WorkflowContext

/**
 * Pure helper for item 10's branch-match guard on the Build tab.
 *
 * The Build tab's action buttons (Refresh, Trigger Build, Trigger Manual Stage) are
 * gated to [WorkflowContext.interactionMode] = Live. When the local branch doesn't
 * match the focused PR's `fromBranch`, the buttons are disabled and the tooltip
 * explains why. Display rows (build list, jobs, logs) stay readable — only writes
 * are gated.
 *
 * Extracted as an `object` (no IntelliJ deps) so it can be unit-tested against pure
 * [WorkflowContext] fixtures without instantiating `BuildDashboardPanel`.
 */
internal object BuildDashboardActionGate {

    fun isLiveMode(ctx: WorkflowContext): Boolean =
        ctx.interactionMode == InteractionMode.Live

    fun readOnlyTooltip(ctx: WorkflowContext): String {
        val focusBranch = ctx.focusPr?.fromBranch
        return if (focusBranch != null)
            "Disabled: local branch doesn't match the focused PR ($focusBranch)"
        else "Disabled: read-only mode"
    }
}

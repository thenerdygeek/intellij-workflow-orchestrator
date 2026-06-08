package com.workflow.orchestrator.pullrequest.ui

import com.intellij.ui.JBColor
import com.workflow.orchestrator.core.bitbucket.BitbucketBuildStatus
import com.workflow.orchestrator.core.ui.StatusColors

/**
 * Pure derivation of the build-status badge from a PR's build statuses (Phase 3 cut — extracted
 * from `PrDetailPanel.updateBuildStatusBadge`). Holds the order-sensitive priority (any FAILED
 * beats any INPROGRESS beats all-SUCCESSFUL) that decides the badge text + color, plus the
 * separate click-to-open URL precedence (FAILED → INPROGRESS → first). Dependency-free over the
 * public `:core` models so the priority logic is unit-testable without the Swing badge.
 */
object BuildStatusBadgeDeriver {

    data class State(val text: String, val color: JBColor, val url: String?)

    fun derive(statuses: List<BitbucketBuildStatus>): State {
        val (text, color) = when {
            statuses.isEmpty() -> "No builds" to StatusColors.INFO
            statuses.any { it.state.equals("FAILED", ignoreCase = true) } ->
                "Build Failed" to StatusColors.ERROR
            statuses.any { it.state.equals("INPROGRESS", ignoreCase = true) } ->
                "Building..." to StatusColors.LINK
            statuses.all { it.state.equals("SUCCESSFUL", ignoreCase = true) } ->
                "Build Passed" to StatusColors.SUCCESS
            else -> "Build Unknown" to StatusColors.INFO
        }
        // Most relevant build's URL for click-to-open (matches the badge priority order).
        val url = statuses.firstOrNull { it.state.equals("FAILED", ignoreCase = true) }?.url
            ?: statuses.firstOrNull { it.state.equals("INPROGRESS", ignoreCase = true) }?.url
            ?: statuses.firstOrNull()?.url
        return State(text, color, url)
    }
}

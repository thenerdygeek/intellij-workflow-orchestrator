package com.workflow.orchestrator.pullrequest.ui

import com.workflow.orchestrator.core.bitbucket.BitbucketMergeStatus
import com.workflow.orchestrator.core.util.HtmlEscape

/**
 * Pure derivation of the merge-button presentation from a PR's merge status (Phase 3 cut —
 * extracted from `PrDetailPanel.updateMergeButtonState`). Keeps the fiddly null / mergeable /
 * conflicted / vetoed branching — including veto-reason aggregation + HTML-escaping and the
 * "leave the button as-is vs force-disable" / "leave the icon vs set it" semantics — out of the
 * Swing method and under test.
 */
object MergeButtonStateDeriver {

    /** Which icon the merge button should show. [UNCHANGED] means leave the current icon as-is. */
    enum class IconKind { WARNING, MERGE, UNCHANGED }

    /**
     * @param icon which icon to set (or [IconKind.UNCHANGED] to leave it)
     * @param forceDisable when true, disable the button; when false, leave its enabled state as
     *   `renderPrHeader` set it (the deriver never *enables* the button)
     * @param tooltip the tooltip to apply (always set)
     */
    data class State(
        val icon: IconKind,
        val forceDisable: Boolean,
        val tooltip: String,
    )

    fun derive(mergeStatus: BitbucketMergeStatus?): State {
        // Could not fetch status — leave icon + enabled untouched, only note it in the tooltip.
        if (mergeStatus == null) {
            return State(IconKind.UNCHANGED, forceDisable = false, tooltip = "Merge status unknown")
        }
        val icon = if (mergeStatus.conflicted) IconKind.WARNING else IconKind.MERGE
        return if (mergeStatus.canMerge) {
            val tooltip = if (mergeStatus.conflicted) {
                "Merge (conflicts detected — may require resolution)"
            } else {
                "Merge this pull request"
            }
            State(icon, forceDisable = false, tooltip = tooltip)
        } else {
            val vetoReasons = mergeStatus.vetoes.joinToString("\n") { it.summaryMessage }
            val tooltip = if (vetoReasons.isNotBlank()) {
                HtmlEscape.escapeHtml("Cannot merge:\n$vetoReasons")
            } else {
                "Cannot merge — preconditions not met"
            }
            State(icon, forceDisable = true, tooltip = tooltip)
        }
    }
}

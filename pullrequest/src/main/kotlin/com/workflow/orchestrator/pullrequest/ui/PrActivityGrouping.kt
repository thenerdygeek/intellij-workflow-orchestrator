package com.workflow.orchestrator.pullrequest.ui

import com.workflow.orchestrator.core.bitbucket.BitbucketCommentAnchor
import com.workflow.orchestrator.core.bitbucket.BitbucketPrActivity

/**
 * Pure grouping of PR activities for the detail panel's activity tab (Phase 3 cut — extracted
 * from `PrDetailPanel.showActivities`). Decides which activities render as inline code comments
 * (anchored to a file:line) versus general timeline entries, and groups the inline ones by
 * anchor. Dependency-free over the public `:core` Bitbucket models, so the inline-vs-general
 * decision is unit-testable without the Swing UI.
 */
object PrActivityGrouping {

    /** Group key for inline comments — file path + line. */
    data class AnchorKey(val path: String, val line: Int)

    /** Split of activities into [inline] code comments and [general] timeline entries. */
    data class Split(
        val inline: List<BitbucketPrActivity>,
        val general: List<BitbucketPrActivity>,
    )

    /** The inline anchor for an activity: its own `commentAnchor`, else its comment's `anchor`. */
    fun anchorOf(activity: BitbucketPrActivity): BitbucketCommentAnchor? =
        activity.commentAnchor ?: activity.comment?.anchor

    /** True when the activity is an inline code comment (an anchor with a non-blank path). */
    fun isInline(activity: BitbucketPrActivity): Boolean {
        val anchor = anchorOf(activity)
        return anchor != null && anchor.path.isNotBlank()
    }

    /** Partition activities into inline code comments and general timeline entries. */
    fun partition(activities: List<BitbucketPrActivity>): Split =
        Split(
            inline = activities.filter { isInline(it) },
            general = activities.filterNot { isInline(it) },
        )

    /** Group inline activities by (path, line), preserving first-seen order. */
    fun groupInlineByAnchor(inline: List<BitbucketPrActivity>): Map<AnchorKey, List<BitbucketPrActivity>> {
        val grouped = linkedMapOf<AnchorKey, MutableList<BitbucketPrActivity>>()
        for (activity in inline) {
            val anchor = anchorOf(activity) ?: continue
            grouped.getOrPut(AnchorKey(anchor.path, anchor.line)) { mutableListOf() }.add(activity)
        }
        return grouped
    }
}

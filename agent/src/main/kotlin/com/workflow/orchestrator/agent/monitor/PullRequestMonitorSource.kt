package com.workflow.orchestrator.agent.monitor

import com.workflow.orchestrator.core.events.WorkflowEvent
import com.workflow.orchestrator.core.services.BitbucketService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow

/**
 * Composite [MonitorSource] that watches a Bitbucket pull request across up to three aspects:
 * - [Aspect.STATE]    — lifecycle transitions (merged, declined, approved) via [PrStateSource]
 * - [Aspect.REVIEWS]  — participant approval-status changes via [PrReviewsSource]
 * - [Aspect.COMMENTS] — blocker count (polling) + total comment count (event-bus) via
 *                       [PrBlockerCountSource] + [PrCommentsTotalSource]
 *
 * All children stamp the PARENT [monitorId] so every emitted event carries the same id.
 * This keeps the MonitorPool handle ↔ MonitorManager per-id budget/flood ledger 1:1.
 *
 * Child sources are started/stopped together.  [stop] is individually wrapped in
 * [runCatching] so a failure in one child never prevents the others from stopping cleanly.
 *
 * @param monitorId    Stable identifier for this composite monitor.
 * @param description  Short label shown in monitor list/status output.
 * @param aspects      Which aspects to watch; empty (after ignoring unknown tokens) → all three.
 * @param bitbucket    [BitbucketService] injected by the tool; drives polling and hydration calls.
 * @param flow         [SharedFlow] of [WorkflowEvent]s (from the project [EventBus]).
 * @param prId         Bitbucket PR id to watch.
 * @param repoName     Optional repo slug override (null = primary repo from settings).
 * @param cs           Lifecycle-bound coroutine scope (from [MonitorTool]).
 */
class PullRequestMonitorSource(
    override val monitorId: String,
    override val description: String,
    aspects: Set<Aspect>,
    bitbucket: BitbucketService,
    flow: SharedFlow<WorkflowEvent>,
    prId: Int,
    repoName: String?,
    cs: CoroutineScope,
) : MonitorSource {

    enum class Aspect { STATE, REVIEWS, COMMENTS }

    /**
     * Ordered list of child sources built from [aspects].
     *
     * Every child receives the PARENT [monitorId] and [description] so all events share
     * the same monitor identifier (critical for the per-monitor wake-budget/flood ledger).
     */
    private val children: List<MonitorSource> = buildList {
        if (Aspect.STATE in aspects) {
            add(PrStateSource(monitorId, description, cs, flow, bitbucket, prId, repoName))
        }
        if (Aspect.REVIEWS in aspects) {
            add(PrReviewsSource(monitorId, description, cs, bitbucket, prId, repoName))
        }
        if (Aspect.COMMENTS in aspects) {
            add(PrBlockerCountSource(monitorId, description, cs, bitbucket, prId, repoName))
            add(PrCommentsTotalSource(monitorId, description, cs, flow, prId))
        }
    }

    /** Number of child sources — exposed for testing. */
    internal val childCount: Int get() = children.size

    override fun start(emit: (MonitorEvent) -> Unit) {
        children.forEach { it.start(emit) }
    }

    override fun stop() {
        children.forEach { runCatching { it.stop() } }
    }

    companion object {

        /**
         * Parse a comma-separated aspect string into a [Set<Aspect>].
         *
         * Rules:
         * - null / blank → all three aspects.
         * - Parsing is case-insensitive and strips surrounding whitespace per token.
         * - Unknown tokens are silently ignored.
         * - If every token is unknown (empty set after ignoring), default to all three.
         */
        fun parseAspects(raw: String?): Set<Aspect> {
            if (raw.isNullOrBlank()) return Aspect.entries.toSet()
            val parsed = raw.split(",")
                .map { it.trim().uppercase() }
                .filter { it.isNotEmpty() }
                .mapNotNull { token ->
                    runCatching { Aspect.valueOf(token) }.getOrNull()
                }
                .toSet()
            return if (parsed.isEmpty()) Aspect.entries.toSet() else parsed
        }
    }
}

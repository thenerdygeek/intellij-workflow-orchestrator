package com.workflow.orchestrator.agent.monitor

import com.workflow.orchestrator.core.events.WorkflowEvent
import com.workflow.orchestrator.core.services.BitbucketService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow

/**
 * [PollingSource] that polls [BitbucketService.getBlockerCommentsCount] for a single PR and emits
 * an [Severity.ALERT] event whenever the blocker-comment count increases (or is non-zero on the
 * first poll).
 *
 * Decreases and unchanged counts are silent — the agent only needs to act when new blockers appear.
 *
 * The pure diff logic lives in [companion object.diffPure] so it can be unit-tested without
 * constructing a source or starting a coroutine.
 */
class PrBlockerCountSource(
    monitorId: String,
    description: String,
    cs: CoroutineScope,
    private val bitbucket: BitbucketService,
    private val prId: Int,
    private val repoName: String?,
) : PollingSource<Int>(monitorId, description, cs) {

    override suspend fun fetch(): Int? {
        val res = bitbucket.getBlockerCommentsCount(prId, repoName)
        return if (res.isError) null else res.data
    }

    override fun diff(previous: Int?, current: Int): List<MonitorEvent> =
        diffPure(monitorId, prId, previous, current)

    companion object {
        /**
         * Pure diff: determines which [MonitorEvent]s (if any) to emit when the blocker count
         * transitions from [previous] (null = first poll) to [current].
         *
         * Rules:
         * - First poll ([previous] == null): emit ALERT only if `current > 0`.
         * - Subsequent poll: emit ALERT only if `current > previous`.
         * - Decrease or unchanged: empty list.
         */
        fun diffPure(
            monitorId: String,
            prId: Int,
            previous: Int?,
            current: Int,
        ): List<MonitorEvent> = when {
            previous == null && current > 0 ->
                listOf(
                    MonitorEvent(
                        monitorId = monitorId,
                        severity = Severity.ALERT,
                        line = "PR #$prId has $current blocker comment(s)",
                    ),
                )

            previous != null && current > previous ->
                listOf(
                    MonitorEvent(
                        monitorId = monitorId,
                        severity = Severity.ALERT,
                        line = "PR #$prId: $current blocker comment(s) (was $previous)",
                    ),
                )

            else -> emptyList()
        }
    }
}

/**
 * [EventBusSource] that listens for [WorkflowEvent.PrCommentsUpdated] events for a specific
 * [prId] and emits a [Severity.NOTABLE] event containing the updated comment total.
 *
 * No [hydrate] override — comment totals have no meaningful terminal state to seed; the agent
 * learns the current count the next time the `:pullrequest` module emits a refresh event.
 *
 * The pure mapping logic lives in [companion object.classify] so it can be unit-tested without
 * constructing the source or starting a coroutine.
 */
class PrCommentsTotalSource(
    monitorId: String,
    description: String,
    cs: CoroutineScope,
    flow: SharedFlow<WorkflowEvent>,
    private val prId: Int,
) : EventBusSource(monitorId, description, cs, flow) {

    override fun map(event: WorkflowEvent): MonitorEvent? =
        classify(monitorId, prId, event)

    companion object {
        /**
         * Pure mapping: returns a [MonitorEvent] when [event] is a [WorkflowEvent.PrCommentsUpdated]
         * for [watchedPrId], or null to ignore the event.
         */
        fun classify(
            monitorId: String,
            watchedPrId: Int,
            event: WorkflowEvent,
        ): MonitorEvent? =
            when {
                event is WorkflowEvent.PrCommentsUpdated && event.prId == watchedPrId ->
                    MonitorEvent(
                        monitorId = monitorId,
                        severity = Severity.NOTABLE,
                        line = "PR #$watchedPrId: ${event.total} comment(s)",
                    )

                else -> null
            }
    }
}

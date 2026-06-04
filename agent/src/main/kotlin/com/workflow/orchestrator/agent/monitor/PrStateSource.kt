package com.workflow.orchestrator.agent.monitor

import com.workflow.orchestrator.core.events.WorkflowEvent
import com.workflow.orchestrator.core.services.BitbucketService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow

/**
 * [EventBusSource] subclass that watches a single Bitbucket pull request's lifecycle state.
 *
 * Subscribes to [WorkflowEvent.PullRequestMerged], [WorkflowEvent.PullRequestDeclined], and
 * [WorkflowEvent.PullRequestApproved] events, emitting [MonitorEvent]s only for the watched [prId].
 *
 * Start-hydration via [hydrate] fetches the PR's current state from [BitbucketService] so a PR
 * that was already merged/declined before subscription is not silently missed.
 *
 * The pure mapping logic lives in [companion object.classify] so it can be unit-tested without
 * constructing the source or starting a coroutine.
 */
class PrStateSource(
    monitorId: String,
    description: String,
    cs: CoroutineScope,
    flow: SharedFlow<WorkflowEvent>,
    private val bitbucket: BitbucketService,
    private val prId: Int,
    private val repoName: String?,
) : EventBusSource(monitorId, description, cs, flow) {

    override fun map(event: WorkflowEvent): MonitorEvent? =
        classify(monitorId, prId, event)

    override suspend fun hydrate(): MonitorEvent? {
        val res = bitbucket.getPullRequestDetail(prId, repoName)
        if (res.isError) return null
        val state = res.data?.state?.uppercase() ?: return null
        return when (state) {
            "MERGED"   -> ev(Severity.NOTABLE, "PR #$prId already MERGED")
            "DECLINED" -> ev(Severity.ALERT,   "PR #$prId already DECLINED")
            else       -> null  // OPEN → nothing to hydrate
        }
    }

    private fun ev(sev: Severity, line: String) = MonitorEvent(monitorId, sev, line)

    companion object {
        /**
         * Pure mapping: determines the [MonitorEvent] (or null to ignore) for a [WorkflowEvent]
         * filtered to [watchedPrId]. Testable without constructing a [PrStateSource].
         */
        fun classify(monitorId: String, watchedPrId: Int, event: WorkflowEvent): MonitorEvent? =
            when (event) {
                is WorkflowEvent.PullRequestMerged   ->
                    if (event.prId == watchedPrId)
                        MonitorEvent(monitorId, Severity.NOTABLE, "PR #$watchedPrId merged")
                    else null

                is WorkflowEvent.PullRequestDeclined ->
                    if (event.prId == watchedPrId)
                        MonitorEvent(monitorId, Severity.ALERT, "PR #$watchedPrId declined")
                    else null

                is WorkflowEvent.PullRequestApproved ->
                    if (event.prId == watchedPrId)
                        MonitorEvent(monitorId, Severity.NOTABLE, "PR #$watchedPrId approved by ${event.byUser}")
                    else null

                else -> null
            }
    }
}

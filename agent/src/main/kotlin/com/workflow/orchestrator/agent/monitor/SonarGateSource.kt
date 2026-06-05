package com.workflow.orchestrator.agent.monitor

import com.workflow.orchestrator.core.events.WorkflowEvent
import com.workflow.orchestrator.core.services.SonarService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow

/**
 * [EventBusSource] subclass that watches a Sonar project's quality gate status.
 *
 * Subscribes to [WorkflowEvent.QualityGateResult] events, emitting [MonitorEvent]s only for the
 * watched [projectKey].
 *
 * Start-hydration via [hydrate] fetches the gate's current state from [SonarService] so a gate
 * that was already ERROR before subscription is not silently missed.
 *
 * The pure mapping logic lives in [companion object.classify] so it can be unit-tested without
 * constructing the source or starting a coroutine.
 */
class SonarGateSource(
    monitorId: String,
    description: String,
    cs: CoroutineScope,
    flow: SharedFlow<WorkflowEvent>,
    private val sonar: SonarService,
    private val projectKey: String,
    private val branch: String?,
) : EventBusSource(monitorId, description, cs, flow) {

    override fun map(event: WorkflowEvent): MonitorEvent? =
        classify(monitorId, projectKey, event)

    override suspend fun hydrate(): MonitorEvent? {
        val res = sonar.getQualityGateStatus(projectKey, branch)
        if (res.isError) return null
        return when (res.data?.status?.uppercase()) {
            "ERROR" -> MonitorEvent(monitorId, Severity.ALERT,   "Sonar $projectKey: quality gate already FAILED (ERROR)")
            "OK"    -> MonitorEvent(monitorId, Severity.NOTABLE, "Sonar $projectKey: quality gate currently PASSING (OK)")
            else    -> null
        }
    }

    companion object {
        /**
         * Pure mapping: determines the [MonitorEvent] (or null to ignore) for a [WorkflowEvent]
         * filtered to [watchedProjectKey]. Testable without constructing a [SonarGateSource].
         */
        fun classify(monitorId: String, watchedProjectKey: String, event: WorkflowEvent): MonitorEvent? =
            if (event is WorkflowEvent.QualityGateResult && event.projectKey == watchedProjectKey)
                if (event.passed)
                    MonitorEvent(monitorId, Severity.NOTABLE, "Sonar $watchedProjectKey: quality gate OK")
                else
                    MonitorEvent(monitorId, Severity.ALERT, "Sonar $watchedProjectKey: quality gate ERROR")
            else null
    }
}

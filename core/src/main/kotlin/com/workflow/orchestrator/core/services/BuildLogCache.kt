package com.workflow.orchestrator.core.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.events.WorkflowEvent
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-project cache of the latest [WorkflowEvent.BuildLogReady] for each chain key.
 *
 * Backstops the `EventBus` `replay = 0` semantics: a panel that subscribes after
 * the event was emitted (e.g. Automation tab opened cold after Bamboo's first
 * poll) can still read the most recent log instead of waiting for the next
 * terminal-status change. Writers ([com.workflow.orchestrator.bamboo] poller)
 * call [put] every time they emit; readers ([com.workflow.orchestrator.automation]
 * panel mount, future Quality / Handover consumers) call [getLatest] before
 * falling back to a REST log fetch.
 *
 * **Keyed by chain key** (e.g. `PROJ-PLANKEY523`), not by the parent/master plan key.
 * This ensures cross-branch isolation: a poll for feature-branch chain `PROJ-PLAN523`
 * never evicts or mis-serves the cache entry for the master chain `PROJ-PLAN`.
 * [WorkflowEvent.BuildLogReady.chainKey] is populated by [BuildMonitorService] from
 * the `planKey` argument it was started with after `autoDetectPlan` resolution.
 */
@Service(Service.Level.PROJECT)
class BuildLogCache {

    private val byChainKey = ConcurrentHashMap<String, WorkflowEvent.BuildLogReady>()

    /** Stores [event] keyed by [WorkflowEvent.BuildLogReady.chainKey] (uppercased). */
    fun put(event: WorkflowEvent.BuildLogReady) {
        byChainKey[event.chainKey.uppercase()] = event
    }

    /**
     * Returns the latest [WorkflowEvent.BuildLogReady] for [chainKey], or null if
     * no event has been cached yet. Lookup is case-insensitive.
     */
    fun getLatest(chainKey: String): WorkflowEvent.BuildLogReady? {
        return byChainKey[chainKey.uppercase()]
    }

    companion object {
        fun getInstance(project: Project): BuildLogCache =
            project.getService(BuildLogCache::class.java)
    }
}

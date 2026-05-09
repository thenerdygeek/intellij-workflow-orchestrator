package com.workflow.orchestrator.core.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.events.WorkflowEvent
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-project cache of the latest [WorkflowEvent.BuildLogReady] for each plan key.
 *
 * Backstops the `EventBus` `replay = 0` semantics: a panel that subscribes after
 * the event was emitted (e.g. Automation tab opened cold after Bamboo's first
 * poll) can still read the most recent log instead of waiting for the next
 * terminal-status change. Writers ([com.workflow.orchestrator.bamboo] poller)
 * call [put] every time they emit; readers ([com.workflow.orchestrator.automation]
 * panel mount, future Quality / Handover consumers) call [getLatest] before
 * falling back to a REST log fetch.
 */
@Service(Service.Level.PROJECT)
class BuildLogCache {

    private val byPlanKey = ConcurrentHashMap<String, WorkflowEvent.BuildLogReady>()

    fun put(event: WorkflowEvent.BuildLogReady) {
        byPlanKey[event.planKey.uppercase()] = event
    }

    fun getLatest(planKey: String): WorkflowEvent.BuildLogReady? {
        return byPlanKey[planKey.uppercase()]
    }

    companion object {
        fun getInstance(project: Project): BuildLogCache =
            project.getService(BuildLogCache::class.java)
    }
}

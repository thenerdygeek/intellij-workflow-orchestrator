package com.workflow.orchestrator.agent.monitor

import com.intellij.openapi.project.Project

/** Set by AgentService (a later task) to route source events into the session's MonitorManager. */
object MonitorBridge {
    @Volatile var router: ((Project, String, MonitorEvent) -> Unit)? = null
    fun emit(project: Project, sessionId: String, event: MonitorEvent) { router?.invoke(project, sessionId, event) }
}

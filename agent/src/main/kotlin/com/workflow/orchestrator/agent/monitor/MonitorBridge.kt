package com.workflow.orchestrator.agent.monitor

import com.intellij.openapi.project.Project
import java.util.concurrent.ConcurrentHashMap

/**
 * Project-scoped router from monitor sources to each project's MonitorManager.
 * AgentService registers a router per project (Task 8); sources call [emit].
 */
object MonitorBridge {
    private val routers = ConcurrentHashMap<Project, (String, MonitorEvent) -> Unit>()
    fun setRouter(project: Project, router: (sessionId: String, event: MonitorEvent) -> Unit) { routers[project] = router }
    fun clearRouter(project: Project) { routers.remove(project) }
    fun emit(project: Project, sessionId: String, event: MonitorEvent) { routers[project]?.invoke(sessionId, event) }
}

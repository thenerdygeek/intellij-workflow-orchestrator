package com.workflow.orchestrator.agent.ui

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
class AgentControllerRegistry {
    @Volatile
    @get:JvmName("controllerRef")
    var controller: AgentController? = null

    /** Explicit accessor for reflective callers (e.g. Settings configurables) that resolve
     *  the controller via [getInstance] without a direct compile-time dependency on [AgentController]. */
    fun getController(): AgentController? = controller

    companion object {
        fun getInstance(project: Project): AgentControllerRegistry =
            project.getService(AgentControllerRegistry::class.java)
    }
}

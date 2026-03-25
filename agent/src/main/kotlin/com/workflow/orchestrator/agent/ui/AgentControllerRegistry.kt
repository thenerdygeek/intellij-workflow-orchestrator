package com.workflow.orchestrator.agent.ui

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
class AgentControllerRegistry {
    @Volatile
    var controller: AgentController? = null

    companion object {
        fun getInstance(project: Project): AgentControllerRegistry =
            project.getService(AgentControllerRegistry::class.java)
    }
}

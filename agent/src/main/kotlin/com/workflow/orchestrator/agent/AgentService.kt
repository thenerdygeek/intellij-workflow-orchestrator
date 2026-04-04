package com.workflow.orchestrator.agent

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.process.ProcessRegistry
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Minimal stub — will be rewritten in Task 8.
 * Keeps only what's needed for compilation:
 * - planModeActive (tools read this)
 * - ProcessRegistry.killAll on dispose
 */
@Service(Service.Level.PROJECT)
class AgentService(private val project: Project) : Disposable {

    override fun dispose() {
        ProcessRegistry.killAll()
    }

    companion object {
        val planModeActive = AtomicBoolean(false)

        fun getInstance(project: Project): AgentService =
            project.service<AgentService>()
    }
}

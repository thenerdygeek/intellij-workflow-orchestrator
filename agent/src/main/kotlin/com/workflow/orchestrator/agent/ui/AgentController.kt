package com.workflow.orchestrator.agent.ui

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

/**
 * Minimal stub — will be rewritten in Task 9.
 * Routes orchestrator callbacks to the dashboard UI.
 */
class AgentController(
    private val project: Project,
    private val dashboard: AgentDashboardPanel
) {
    companion object {
        private val LOG = Logger.getInstance(AgentController::class.java)
    }

    fun executeTask(prompt: String) {
        LOG.info("AgentController.executeTask stub called: ${prompt.take(80)}")
        // TODO: Wire to new AgentLoop
    }

    fun resumeSession(sessionId: String) {
        LOG.info("AgentController.resumeSession stub called: $sessionId")
        // TODO: Wire to new session restore
    }

    fun stop() {
        LOG.info("AgentController.stop stub called")
    }

    fun dispose() {
        // no-op stub
    }
}

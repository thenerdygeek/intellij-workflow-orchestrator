package com.workflow.orchestrator.agent.listeners

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.workflow.orchestrator.agent.settings.AgentSettings

/**
 * Minimal stub — will be rewritten when session persistence is reimplemented.
 * Checks for interrupted agent sessions on IDE startup.
 */
class AgentStartupActivity : ProjectActivity {

    companion object {
        private val LOG = Logger.getInstance(AgentStartupActivity::class.java)
    }

    override suspend fun execute(project: Project) {
        // Only check if agent is enabled
        val settings = try {
            AgentSettings.getInstance(project)
        } catch (_: Exception) {
            return
        }
        if (!settings.state.agentEnabled) return

        // TODO: Check for interrupted sessions when session persistence is reimplemented
        LOG.info("AgentStartupActivity: agent enabled, no interrupted session checks yet")
    }
}
